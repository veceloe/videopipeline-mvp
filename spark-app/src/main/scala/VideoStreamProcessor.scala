import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.DataFrame
import java.nio.file.{Files, Path => JPath}
import org.apache.hadoop.fs.{FileSystem, Path}
import scala.io.Source
import ujson._

object VideoStreamProcessor {
  def main(args: Array[String]): Unit = {
    System.setProperty("yt.proxy", "ytsaurus:80")

    val spark = SparkSession.builder()
      .appName("VideoStreamProcessor")
      .master("local[*]")
      .config("spark.hadoop.fs.yt.impl", "tech.ytsaurus.spyt.fs.YtFileSystem")
      .config("spark.hadoop.fs.AbstractFileSystem.yt.impl", "tech.ytsaurus.spyt.fs.YtAbstractFileSystem")
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")
    val hConf = spark.sparkContext.hadoopConfiguration

    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka:9092")
      .option("subscribe", "video_stream")
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .load()

    val segments = kafkaDF.selectExpr("cast(key as string) as name", "value as bytes")

    import spark.implicits._

    val query = segments.writeStream.foreachBatch { (batchDF: DataFrame, batchId: Long) =>
      batchDF.persist()
      batchDF.collect().foreach { row =>
        val name = row.getAs[String]("name")
        val bytes = row.getAs[Array[Byte]]("bytes")
        val tmp = Files.createTempFile(s"seg-$batchId-", s"-$name.mp4")
        Files.write(tmp, bytes)

        val probeJson = {
          val pb = new ProcessBuilder("ffprobe", "-v", "quiet", "-print_format", "json", "-show_streams", "-show_format", tmp.toString)
            .redirectErrorStream(true)
          val pr = pb.start()
          val out = Source.fromInputStream(pr.getInputStream).mkString
          pr.waitFor()
          out
        }

        val parsed = ujson.read(probeJson)
        val streams = parsed("streams").arr
        val videoStream = streams.find(_("codec_type").str == "video").get
        val audioStream = streams.find(_("codec_type").str == "audio").get
        val fmt = parsed("format")

        val timestampMs = System.currentTimeMillis()
        val sizeBytes = Files.size(tmp)
        val sizeMb = sizeBytes.toDouble / 1024 / 1024
        val videoCodec = videoStream("codec_name").str
        val audioCodec = audioStream("codec_name").str
        val resolution = s"${videoStream("width").num.toInt}x${videoStream("height").num.toInt}"
        val frameRate = {
          val parts = videoStream("r_frame_rate").str.split('/')
          parts(0).toDouble / parts(1).toDouble
        }
        val bitrateKbps = (fmt("bit_rate").str.toLong / 1000).toInt
        val colorDepth = videoStream.obj.get("bits_per_raw_sample").map(_.num.toInt).getOrElse(8)
        val duration = fmt("duration").str.toDouble

        val meta = Seq((name, batchId.toLong, timestampMs, sizeBytes, sizeMb, videoCodec,
          audioCodec, resolution, frameRate, bitrateKbps, colorDepth, duration))
        val df = spark.createDataFrame(meta).toDF(
          "segment_name", "batch_id", "timestamp_ms", "size_bytes", "size_mb",
          "video_codec", "audio_codec", "resolution", "frame_rate",
          "bitrate_kbps", "color_depth_bits", "duration_seconds"
        )

        df.write
          .format("yt")
          .mode("append")
          .option("path", "yt://home/video_metadata")
          .save()

        Files.delete(tmp)
      }
      batchDF.unpersist()
    }
      .option("checkpointLocation", "/tmp/spark-checkpoints/video")
      .trigger(org.apache.spark.sql.streaming.Trigger.ProcessingTime("10 seconds"))
      .start()

    query.awaitTermination()
  }
}