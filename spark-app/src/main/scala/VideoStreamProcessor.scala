import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import java.nio.file.{Files, Paths}
import org.apache.hadoop.fs.{FileSystem, Path}

object VideoStreamProcessor {
  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder()
      .appName("VideoStreamProcessor")
      .master("local[*]")        // внутри контейнера
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")

    // ==== S3 (MinIO) конфиги ====
    val hconf = spark.sparkContext.hadoopConfiguration
    hconf.set("fs.s3a.endpoint", "http://minio:9000")
    hconf.set("fs.s3a.access.key", "minioadmin")
    hconf.set("fs.s3a.secret.key", "minioadmin")
    hconf.set("fs.s3a.path.style.access", "true")
    hconf.set("fs.s3a.connection.ssl.enabled", "false")

    // ==== Kafka источник ====
    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka:9092")
      .option("subscribe", "video_stream")
      .option("startingOffsets", "earliest")
      .load()

    // value: binary сегмент
    val segments = kafkaDF.select(
      col("key").cast("string").as("name"),
      col("value").as("bytes")
    )

    // foreachBatch: запись сегмента в S3 и мета-инфы в YTsaurus
    val query = segments.writeStream
      .foreachBatch { (batch: DataFrame, id: Long) =>
        import batch.sparkSession.implicits._

        batch.collect().foreach { row =>
          val name = row.getAs[String]("name")
          val bytes = row.getAs[Array[Byte]]("bytes")
          val localTmp = Files.createTempFile("seg-", ".mp4")
          Files.write(localTmp, bytes)

          // ---- S3 ----
          val s3Path = s"s3a://video-bucket/segments/$name"
          val fs = FileSystem.get(hconf)
          fs.copyFromLocalFile(false, true, new Path(localTmp.toString), new Path(s3Path))

          // ---- метаданные ----
          val meta = Seq((name, System.currentTimeMillis(), bytes.length))
            .toDF("segment_name", "timestamp_ms", "size_bytes")

          meta.write
            .format("yt")
            .mode("append")
            .option("path", "//home/video_metadata")
            .save()
        }
      }
      .option("checkpointLocation", "/tmp/spark-checkpoints/video")
      .start()

    query.awaitTermination()
  }
} 