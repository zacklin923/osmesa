package osmesa.common.streaming

import java.net.URI
import java.util

import org.apache.spark.sql.Row
import org.apache.spark.sql.sources.v2.DataSourceOptions
import org.apache.spark.sql.sources.v2.reader.streaming.Offset
import org.apache.spark.sql.sources.v2.reader.{DataReader, DataReaderFactory}
import org.apache.spark.sql.types._
import osmesa.common.model.Element

import scala.collection.JavaConverters._

case class ChangesStreamBatchTask(baseURI: URI, start: SequenceOffset, end: SequenceOffset)
  extends DataReaderFactory[Row] {
  override def createDataReader(): DataReader[Row] =
    new ChangesStreamBatchReader(baseURI, start, end)
}

class ChangesStreamBatchReader(baseURI: URI, start: SequenceOffset, end: SequenceOffset)
  extends ReplicationStreamBatchReader[Element](baseURI, start, end) {

  override def getSequence(baseURI: URI, sequence: Int): Seq[Element] =
    ChangesSource.getSequence(baseURI, sequence)

  override def get(): Row = {
    val change = items(index)

    val members = change.members.map(members => members.map(m => Row(m._type, m.ref, m.role)))

    Row(
      currentOffset.sequence,
      change._type,
      change.id,
      change.tags,
      change.lat.orNull,
      change.lon.orNull,
      change.nds.orNull,
      members.orNull,
      change.changeset,
      change.timestamp,
      change.uid,
      change.user,
      change.version,
      change.visible
    )
  }
}

class ChangesMicroBatchReader(options: DataSourceOptions, checkpointLocation: String)
  extends ReplicationStreamMicroBatchReader(options, checkpointLocation) {
  // TODO extract me
  val ChangeSchema = StructType(
    StructField("sequence", IntegerType) ::
      StructField("_type", ByteType, nullable = false) ::
      StructField("id", LongType, nullable = false) ::
      StructField("tags",
        MapType(StringType, StringType, valueContainsNull = false),
        nullable = false) ::
      StructField("lat", DataTypes.createDecimalType(9, 7), nullable = true) ::
      StructField("lon", DataTypes.createDecimalType(10, 7), nullable = true) ::
      StructField("nds", DataTypes.createArrayType(LongType), nullable = true) ::
      StructField(
        "members",
        DataTypes.createArrayType(
          StructType(
            StructField("_type", ByteType, nullable = false) ::
              StructField("ref", LongType, nullable = false) ::
              StructField("role", StringType, nullable = false) ::
              Nil
          )
        ),
        nullable = true
      ) ::
      StructField("changeset", LongType, nullable = false) ::
      StructField("timestamp", TimestampType, nullable = false) ::
      StructField("uid", LongType, nullable = false) ::
      StructField("user", StringType, nullable = false) ::
      StructField("version", IntegerType, nullable = false) ::
      StructField("visible", BooleanType, nullable = false) ::
      Nil)

  private val baseURI = new URI(
    options
      .get("base_uri")
      .orElse("https://planet.osm.org/replication/minute/"))

  override def getCurrentOffset: SequenceOffset =
    ChangesSource.createOffsetForCurrentSequence(baseURI)

  override def commit(end: Offset): Unit =
    logInfo(s"Change sequence $end processed.")

  override def readSchema(): StructType = ChangeSchema

  override def createDataReaderFactories(): util.List[DataReaderFactory[Row]] =
    List(
      ChangesStreamBatchTask(baseURI, start.get, end.get)
        .asInstanceOf[DataReaderFactory[Row]]).asJava
}
