package osmesa.functions

import java.sql.Timestamp

import com.google.common.collect.{Range, RangeMap, TreeRangeMap}
import com.vividsolutions.jts.geom._
import geotrellis.vector.io._
import geotrellis.vector.{Line, MultiPolygon, Polygon}
import org.apache.log4j.Logger
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.udf
import osmesa.ProcessOSM

import scala.annotation.tailrec
import scala.collection.GenTraversable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

package object osm {
  // Using tag listings from [id-area-keys](https://github.com/osmlab/id-area-keys).
  private val AREA_KEYS: Map[String, Map[String, Boolean]] = Map(
    "addr:*" -> Map(),
    "aerialway" -> Map(
      "cable_car" -> true,
      "chair_lift" -> true,
      "drag_lift" -> true,
      "gondola" -> true,
      "goods" -> true,
      "magic_carpet" -> true,
      "mixed_lift" -> true,
      "platter" -> true,
      "rope_tow" -> true,
      "t-bar" -> true
    ),
    "aeroway" -> Map(
      "runway" -> true,
      "taxiway" -> true
    ),
    "amenity" -> Map(
      "bench" -> true
    ),
    "area:highway" -> Map(),
    "attraction" -> Map(
      "dark_ride" -> true,
      "river_rafting" -> true,
      "train" -> true,
      "water_slide" -> true
    ),
    "building" -> Map(),
    "camp_site" -> Map(),
    "club" -> Map(),
    "craft" -> Map(),
    "emergency" -> Map(
      "designated" -> true,
      "destination" -> true,
      "no" -> true,
      "official" -> true,
      "private" -> true,
      "yes" -> true
    ),
    "golf" -> Map(
      "hole" -> true,
      "lateral_water_hazard" -> true,
      "water_hazard" -> true
    ),
    "healthcare" -> Map(),
    "historic" -> Map(),
    "industrial" -> Map(),
    "junction" -> Map(
      "roundabout" -> true
    ),
    "landuse" -> Map(),
    "leisure" -> Map(
      "slipway" -> true,
      "track" -> true
    ),
    "man_made" -> Map(
      "breakwater" -> true,
      "crane" -> true,
      "cutline" -> true,
      "embankment" -> true,
      "groyne" -> true,
      "pier" -> true,
      "pipeline" -> true
    ),
    "military" -> Map(),
    "natural" -> Map(
      "cliff" -> true,
      "coastline" -> true,
      "ridge" -> true,
      "tree_row" -> true
    ),
    "office" -> Map(),
    "piste:type" -> Map(),
    "place" -> Map(),
    "playground" -> Map(
      "balancebeam" -> true,
      "slide" -> true,
      "zipwire" -> true
    ),
    "power" -> Map(
      "line" -> true,
      "minor_line" -> true
    ),
    "public_transport" -> Map(
      "platform" -> true
    ),
    "shop" -> Map(),
    "tourism" -> Map(),
    "waterway" -> Map(
      "canal" -> true,
      "dam" -> true,
      "ditch" -> true,
      "drain" -> true,
      "river" -> true,
      "stream" -> true,
      "weir" -> true
    )
  )

  private val MULTIPOLYGON_TYPES = Set("multipolygon", "boundary")

  private val BOOLEAN_VALUES = Set("yes", "no", "true", "false", "1", "0")

  private val TRUTHY_VALUES = Set("yes", "true", "1")

  private lazy val logger = Logger.getRootLogger

  private val _isArea = (tags: Map[String, String]) =>
    tags match {
      case _ if tags.contains("area") && BOOLEAN_VALUES.contains(tags("area").toLowerCase) =>
        TRUTHY_VALUES.contains(tags("area").toLowerCase)
      case _ =>
        // see https://github.com/osmlab/id-area-keys (values are inverted)
        val matchingKeys = tags.keySet.intersect(AREA_KEYS.keySet)
        matchingKeys.exists(k => !AREA_KEYS(k).contains(tags(k)))
    }

  val isArea: UserDefinedFunction = udf(_isArea)

  private val _isMultiPolygon = (tags: Map[String, String]) =>
    tags.contains("type") && MULTIPOLYGON_TYPES.contains(tags("type").toLowerCase)

  val isMultiPolygon: UserDefinedFunction = udf(_isMultiPolygon)

  val collectWay = new WayAssembler

  val collectRelation = new RelationAssembler

  class AssemblyException(msg: String) extends Exception(msg)

  /**
    * Tests whether two {@link CoordinateSequence}s are equal.
    * To be equal, the sequences must be the same length.
    * They do not need to be of the same dimension,
    * but the ordinate values for the smallest dimension of the two
    * must be equal.
    * Two <code>NaN</code> ordinates values are considered to be equal.
    *
    * Ported to Scala from JTS 1.15.0
    *
    * @param cs1 a CoordinateSequence
    * @param cs2 a CoordinateSequence
    * @return true if the sequences are equal in the common dimensions
    */
  private def isEqual(cs1: CoordinateSequence, cs2: CoordinateSequence): Boolean = {
    if (cs1.size != cs2.size) {
      false
    } else {
      val dim = Math.min(cs1.getDimension, cs2.getDimension)
      (0 until cs1.size).forall(i => {
        (0 until dim).forall(d => {
          val v1 = cs1.getOrdinate(i, d)
          val v2 = cs2.getOrdinate(i, d)

          v1 == v2 || (v1 == Double.NaN && v2 == Double.NaN)
        })
      })
    }
  }

  class ReversedCoordinateSequence(sequence: CoordinateSequence) extends CoordinateSequence {
    private def getIndex(i: Int): Int = size - 1 - i

    override def getDimension: Int = sequence.getDimension

    override def getCoordinate(i: Int): Coordinate = sequence.getCoordinate(getIndex(i))

    override def getCoordinateCopy(i: Int): Coordinate = sequence.getCoordinateCopy(getIndex(i))

    override def getCoordinate(index: Int, coord: Coordinate): Unit = sequence.getCoordinate(getIndex(index), coord)

    override def getX(index: Int): Double = sequence.getX(getIndex(index))

    override def getY(index: Int): Double = sequence.getY(getIndex(index))

    override def getOrdinate(index: Int, ordinateIndex: Int): Double =
      sequence.getOrdinate(getIndex(index), ordinateIndex)

    override def size(): Int = sequence.size

    override def setOrdinate(index: Int, ordinateIndex: Int, value: Double): Unit =
      sequence.setOrdinate(getIndex(index), ordinateIndex, value)

    private lazy val coordinates: Array[Coordinate] = {
      val coords = new Array[Coordinate](size())

      for (i <- size - 1 to 0) {
        coords(i) = getCoordinate(i)
      }

      coords
    }

    override def toCoordinateArray: Array[Coordinate] = coordinates

    override def expandEnvelope(env: Envelope): Envelope = sequence.expandEnvelope(env)

    override def clone(): AnyRef = new ReversedCoordinateSequence(sequence)
  }

  class PartialCoordinateSequence(sequence: CoordinateSequence, offset: Int) extends CoordinateSequence {
    private lazy val _size: Int = sequence.size() - offset

    private lazy val coordinates: Array[Coordinate] = {
      val coords = new Array[Coordinate](size())

      for (i <- 0 until size) {
        coords(i) = getCoordinate(i)
      }

      coords
    }

    override def getDimension: Int = sequence.getDimension

    override def getCoordinate(i: Int): Coordinate = sequence.getCoordinate(offset + i)

    override def getCoordinateCopy(i: Int): Coordinate = sequence.getCoordinateCopy(offset + i)

    override def getCoordinate(index: Int, coord: Coordinate): Unit = sequence.getCoordinate(offset + index, coord)

    override def getX(index: Int): Double = sequence.getX(offset + index)

    override def getY(index: Int): Double = sequence.getY(offset + index)

    override def getOrdinate(index: Int, ordinateIndex: Int): Double =
      sequence.getOrdinate(offset + index, ordinateIndex)

    override def size(): Int = _size

    override def setOrdinate(index: Int, ordinateIndex: Int, value: Double): Unit =
      sequence.setOrdinate(offset + index, ordinateIndex, value)

    override def toCoordinateArray: Array[Coordinate] = coordinates

    override def expandEnvelope(env: Envelope): Envelope = {
      for (i <- 0 until size) {
        env.expandToInclude(getX(i), getY(i))
      }

      env
    }

    override def clone(): AnyRef = new PartialCoordinateSequence(sequence, offset)
  }

  // rather than being a nested set of CoordinateSequences, this is a mutable wrapper to avoid deep call stacks
  class VirtualCoordinateSequence(sequences: Seq[CoordinateSequence]) extends CoordinateSequence {
    private val rangeMap: RangeMap[Integer, CoordinateSequence] = TreeRangeMap.create[Integer, CoordinateSequence]

    sequences.zip(sequences.map(_.size).scanLeft(0)(_ + _).dropRight(1))
      .map { case (seq, offset) => (seq, Range.closed(offset: Integer, offset + seq.size - 1: Integer)) }
      .foreach { case (seq, range) => rangeMap.put(range, seq)}

    private var dimension: Int = sequences.map(_.getDimension).min

    private var _size: Int = sequences.map(_.size).sum

    private def getSequence(i: Int): (CoordinateSequence, Int) = {
      val entry = rangeMap.getEntry(i: Integer)

      (entry.getValue, i - entry.getKey.lowerEndpoint)
    }

    def append(sequence: CoordinateSequence): VirtualCoordinateSequence = {
      val upperEndpoint = rangeMap.span.upperEndpoint
      val range = Range.closed(upperEndpoint + 1: Integer, upperEndpoint + sequence.size: Integer)
      rangeMap.put(range, sequence)

      dimension = Math.min(dimension, sequence.getDimension)
      _size += sequence.size

      this
    }

    override def getDimension: Int = dimension

    override def getCoordinate(i: Int): Coordinate = {
      val (sequence, index) = getSequence(i)

      // bypass PackedCoordinateSequence.getCoordinate to prevent caching and associated allocation
      new Coordinate(sequence.getX(index), sequence.getY(index))
    }

    override def getCoordinateCopy(i: Int): Coordinate = {
      val (sequence, index) = getSequence(i)

      sequence.getCoordinateCopy(index)
    }

    override def getCoordinate(i: Int, coord: Coordinate): Unit = {
      val (sequence, index) = getSequence(i)

      sequence.getCoordinate(index, coord)
    }

    override def getX(i: Int): Double = {
      val (sequence, index) = getSequence(i)

      sequence.getX(index)
    }

    override def getY(i: Int): Double = {
      val (sequence, index) = getSequence(i)

      sequence.getY(index)
    }

    override def getOrdinate(i: Int, ordinateIndex: Int): Double = {
      val (sequence, index) = getSequence(i)

      sequence.getOrdinate(index, ordinateIndex)
    }

    override def size(): Int = _size

    override def setOrdinate(i: Int, ordinateIndex: Int, value: Double): Unit = {
      val (sequence, index) = getSequence(i)

      sequence.setOrdinate(index, ordinateIndex, value)
    }

    // TODO this should be invalidated after append (but it doesn't actually matter because all of the appending will
    // occur ahead of time)
    private lazy val coordinates: Array[Coordinate] = {
      val coords = new Array[Coordinate](size())

      for (i <- 0 until size) {
        coords(i) = getCoordinate(i)
      }

      coords
    }

    override def toCoordinateArray: Array[Coordinate] = coordinates

    override def expandEnvelope(env: Envelope): Envelope = {
      for (i <- 0 until size) {
        env.expandToInclude(getX(i), getY(i))
      }

      env
    }

    override def clone(): AnyRef = {
      // we're already playing fast and loose
      this
    }
  }

  // create fully-formed rings from line segments
  @tailrec
  private def connectSegments(segments: GenTraversable[VirtualCoordinateSequence], rings: Seq[CoordinateSequence] = Vector.empty[CoordinateSequence]): GenTraversable[CoordinateSequence] = {
    segments match {
      case Nil => rings
      case Seq(h, t @ _ *) if h.getX(0) == h.getX(h.size - 1) && h.getY(0) == h.getY(h.size - 1) =>
        connectSegments(t, rings :+ h)
      case Seq(h, t @ _ *) =>
        val x = h.getX(h.size - 1)
        val y = h.getY(h.size - 1)

        connectSegments(t.find(line => x == line.getX(0) && y == line.getY(0)) match {
          case Some(next) =>
            h.append(new PartialCoordinateSequence(next, 1)) +: t.filterNot(line => isEqual(line, next))
          case None =>
            t.find(line => x == line.getX(line.size - 1) && y == line.getY(line.size - 1)) match {
              case Some(next) =>
                h.append(new PartialCoordinateSequence(new ReversedCoordinateSequence(next), 1)) +: t.filterNot(line => isEqual(line, next))
              case None => throw new AssemblyException("Unable to connect segments.")
            }
        }, rings)
    }
  }

  private def connectSegments(segments: GenTraversable[Line]): GenTraversable[Polygon] = {
//    connectSegments(segments.map(_.jtsGeom.getCoordinateSequence): List[CoordinateSequence]).map(geometryFactory.createPolygon).map(Polygon(_))
    // requires patched geotrellis
    connectSegments(segments.map(_.jtsGeom.getCoordinateSequence).map(s => new VirtualCoordinateSequence(Seq(s)))).map(Polygon(_))
  }

  @tailrec
  private def dissolveRings(rings: GenTraversable[Polygon], dissolvedOuters: Seq[Polygon] = Vector.empty[Polygon], dissolvedInners: Seq[Polygon] = Vector.empty[Polygon]): (Seq[Polygon], Seq[Polygon]) = {
    rings match {
      case Nil => (dissolvedOuters, dissolvedInners)
      case Seq(h, t @ _ *) =>
        val prepared = h.prepare
        t.filter(r => prepared.touches(r)) match {
          case touching if touching.isEmpty => dissolveRings(t.filterNot(r => prepared.touches(r)), dissolvedOuters :+ Polygon(h.exterior), dissolvedInners ++ h.holes.map(Polygon(_)))
          case touching =>
            val dissolved = touching.foldLeft(Vector(h)) {
              case (rs, r2) =>
                rs.flatMap { r =>
                  r.union(r2).toGeometry match {
                    case Some(p: Polygon) => Vector(p)
                    case Some(mp: MultiPolygon) => mp.polygons
                    case _ => throw new AssemblyException("Union failed.")
                  }
                }
            }

            val remaining = t.filterNot(r => prepared.touches(r))
            val preparedRemaining = remaining.map(_.prepare)
            val retryRings = dissolved.filter(d => preparedRemaining.exists(r => r.touches(d)))
            val newRings = dissolved.filter(d => !preparedRemaining.exists(r => r.touches(d)))

            dissolveRings(retryRings ++ remaining, dissolvedOuters ++ newRings.map(_.exterior).map(Polygon(_)), dissolvedInners ++ newRings.flatMap(_.holes).map(Polygon(_)))
        }
    }
  }

  def buildMultiPolygon(id: Long, version: Long, timestamp: Timestamp, types: Seq[Byte], roles: Seq[String], wkbs: Seq[Array[Byte]]): Option[Array[Byte]] = {
    if (types.zip(wkbs).exists { case (t, g) => t == ProcessOSM.WAY_TYPE && Option(g).isEmpty }) {
      // bail early if null values are present where they should exist (members w/ type=way)
      logger.debug(s"Incomplete relation: $id @ $version ($timestamp)")
      None
    } else {
      val bytes = wkbs.map(Option(_)).filter(_.isDefined).map(_.get).map(_.length).sum

      if (bytes > 500000) {
        // bail early to avoid additional allocations associated with parsing the WKB
        logger.warn(s"Dropping $id @ $version ($timestamp) due to size (${bytes.formatted("%,d")} bytes).")
        None
      } else {
        val geoms = wkbs.map(Option(_).map(_.readWKB) match {
          case Some(geom: Polygon) => geom.as[Polygon].map(_.exterior)
          case Some(geom) => geom.as[Line]
          case None => None
        })

        val vertexCount = geoms.filter(_.isDefined).map(_.get).map(_.vertexCount).sum

        logger.warn(s"${vertexCount.formatted("%,d")} vertices (${bytes.formatted("%,d")} bytes) from ${types.size} members in $id @ $version ($timestamp)")

        val members: Seq[(String, Line)] = roles.zip(geoms)
          .filter(_._2.isDefined)
          .map(x => (x._1, x._2.get))

        val (completeOuters, completeInners, completeUnknowns, partialOuters, partialInners, partialUnknowns) = members.foldLeft((Vector.empty[Polygon], Vector.empty[Polygon], Vector.empty[Polygon], Vector.empty[Line], Vector.empty[Line], Vector.empty[Line])) {

          case ((co, ci, cu, po, pi, pu), (role, line: Line)) =>
            role match {
              case "outer" if line.isClosed && line.vertexCount >= 4 => (co :+ Polygon(line), ci, cu, po, pi, pu)
              case "outer" => (co, ci, cu, po :+ line, pi, pu)
              case "inner" if line.isClosed && line.vertexCount >= 4 => (co, ci :+ Polygon(line), cu, po, pi, pu)
              case "inner" => (co, ci, cu, po, pi :+ line, pu)
              case "" if line.isClosed && line.vertexCount >= 4 => (co, ci, cu :+ Polygon(line), po, pi, pu)
              case "" => (co, ci, cu, po, pi, pu :+ line)
              case _ => (co, ci, cu, po, pi, pu)
            }
        }

        val future = Future {
          try {
            val unknowns: Seq[Polygon] = completeUnknowns ++ connectSegments(partialUnknowns.sortWith(_.length > _
              .length))

            val (outers, inners) = unknowns.foldLeft((completeOuters ++ connectSegments(partialOuters.sortWith(_.length > _.length)), completeInners ++ connectSegments(partialInners.sortWith(_.length > _.length)))) {
              case ((o: Seq[Polygon], i: Seq[Polygon]), u) =>
                if (o.exists(_.contains(u))) {
                  (o, i :+ u)
                } else {
                  (o :+ u, i)
                }
            }

            val rings = outers ++ inners
            val preparedRings = rings.map(_.prepare)

            // reclassify rings according to their topology (ignoring roles)
            val (classifiedOuters, classifiedInners) = rings.sortWith(_.area > _.area) match {
              case Seq(h, t@_ *) => t.foldLeft((Vector(h), Vector.empty[Polygon])) {
                case ((os, is), ring) =>
                  // check the number of containing elements
                  preparedRings.count(r => r.geom != ring && r.contains(ring)) % 2 match {
                    // if even, it's an outer ring
                    case 0 => (os :+ ring, is)
                    // if odd, it's an inner ring
                    case 1 => (os, is :+ ring)
                  }
              }
              case rs if rs.isEmpty => (Vector.empty[Polygon], Vector.empty[Polygon])
            }

            val (dissolvedOuters, addlInners) = dissolveRings(classifiedOuters)
            val (dissolvedInners, addlOuters) = dissolveRings(classifiedInners.map(_.exterior).map(Polygon(_)) ++ addlInners)

            val (polygons, _) = (dissolvedOuters ++ addlOuters)
              // sort by size (descending) to use rings as part of the largest available polygon
              .sortWith(_.area > _.area)
              // only use inners once if they're contained by multiple outer rings
              .foldLeft((Vector.empty[Polygon], dissolvedInners)) {
              case ((ps, is), (outer)) =>
                val preparedOuter = outer.prepare
                (ps :+ Polygon(outer.exterior, is.filter(inner => preparedOuter.contains(inner)).map(_.exterior)), is.filterNot(inner => preparedOuter.contains(inner)))
            }

            Some(polygons match {
              case Vector(p: Polygon) => p.toWKB(4326)
              case ps => MultiPolygon(ps).toWKB(4326)
            })
          } catch {
            case e@(_: AssemblyException | _: IllegalArgumentException | _: TopologyException) =>
              logger.warn(s"Could not reconstruct relation $id @ $version ($timestamp): ${e.getMessage}")
              None
            case e: Throwable =>
              logger.warn(s"Could not reconstruct relation $id @ $version ($timestamp): $e")
              e.getStackTrace.foreach(logger.warn)
              None
          }
        }

        Try(Await.result(future, 1 second)) match {
          case Success(res) => res
          case Failure(_) =>
            logger.warn(s"Could not reconstruct relation $id @ $version ($timestamp): Assembly timed out.")
            None
        }
      }
    }
  }
}
