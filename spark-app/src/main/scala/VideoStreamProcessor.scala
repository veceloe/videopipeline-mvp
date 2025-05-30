import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import java.nio.file.Files
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.conf.Configuration

object VideoStreamProcessor {
  def main(args: Array[String]): Unit = {

    val ytProxyValue = "ytsaurus:80"
    System.setProperty("yt.proxy", ytProxyValue)
    println(s"""PROGRAMMATICALLY SET System.setProperty("yt.proxy", "$ytProxyValue")""")

    println("Attempting to build SparkSession with YTsaurus configurations...")

    val spark = SparkSession.builder()
      .appName("VideoStreamProcessor")
      .master("local[*]")
      .config("spark.yt.proxy", ytProxyValue)
      .config("spark.yt.read.ignoreNullable", "true")
      .config("spark.yt.write.ignoreNullable", "true")
      .config("spark.hadoop.yt.proxy", ytProxyValue) // Дублируем для HadoopConf
      .getOrCreate()

    println("SparkSession built successfully.")
    spark.sparkContext.setLogLevel("WARN")

    val globalHConf = spark.sparkContext.hadoopConfiguration

    println("---- Default FileSystem (from globalHConf) ----")
    try {
      val defaultFs = FileSystem.get(globalHConf)
      println(s"Default FS scheme: ${defaultFs.getScheme}, Class: ${defaultFs.getClass.getName}, URI: ${defaultFs.getUri}")
    } catch {
      case e: Throwable => println(s"Error getting default FS: ${e.getMessage}")
    }

    // УДАЛЕН ТЕСТОВЫЙ БЛОК ДЛЯ FileSystem.get("yt://...") ЗДЕСЬ

    println("---- SparkConf effective settings at Driver Start ----")
    spark.conf.getAll.foreach { case (k, v) =>
      if (k.contains("yt.") || k.contains("s3a.")) {
        println(s"SparkConf: $k = $v")
      }
    }
    println("---- HadoopConf effective settings at Driver Start (via SparkContext globalHConf) ----")
    println(s"HadoopConf spark.hadoop.yt.proxy (from globalHConf): ${globalHConf.get("spark.hadoop.yt.proxy", "NOT SET IN globalHConf")}")
    println(s"HadoopConf yt.proxy (direct from globalHConf): ${globalHConf.get("yt.proxy", "NOT SET IN globalHConf")}")
    println(s"System Property yt.proxy (after programmatic set): ${System.getProperty("yt.proxy", "NOT SET AS SYSPROP")}")

    globalHConf.set("fs.s3a.endpoint", "http://minio:9000")
    globalHConf.set("fs.s3a.access.key", "minioadmin")
    globalHConf.set("fs.s3a.secret.key", "minioadmin")
    globalHConf.set("fs.s3a.path.style.access", "true")
    globalHConf.set("fs.s3a.connection.ssl.enabled", "false")
    globalHConf.set("fs.s3a.attempts.maximum", "3")
    globalHConf.set("fs.s3a.connection.timeout", "15000")
    globalHConf.set("fs.s3a.socket.timeout", "15000")

    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "kafka:9092")
      .option("subscribe", "video_stream")
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .load()

    val segments = kafkaDF.select(
      col("key").cast("string").as("name"),
      col("value").as("bytes")
    )

    println("Starting VideoStreamProcessor query (streaming)...")

    val query = segments.writeStream
      .foreachBatch { (batchDF: DataFrame, batchId: Long) =>
        println(s"--- Starting processing for batchId: $batchId ---")

        val batchSparkSession = batchDF.sparkSession
        import batchSparkSession.implicits._

        val batchHConf = batchSparkSession.sparkContext.hadoopConfiguration

        val s3Uri = new java.net.URI("s3a://video-bucket/")
        var s3fs: FileSystem = null
        try {
          s3fs = FileSystem.get(s3Uri, batchHConf)
          println(s"[Batch $batchId] Successfully got S3 FileSystem for URI: $s3Uri, Class: ${s3fs.getClass.getName}")
        } catch {
          case e: Throwable =>
            println(s"[Batch $batchId] CRITICAL: Error getting S3 FileSystem: ${e.getMessage}")
            e.printStackTrace()
            throw new RuntimeException(s"Failed to get S3 FileSystem for batch $batchId", e)
        }

        val collectedRows = batchDF.collect()
        if (collectedRows.nonEmpty) {
          println(s"[Batch $batchId] Processing ${collectedRows.length} segments.")
          collectedRows.foreach { row =>
            val name = row.getAs[String]("name")
            val bytes = row.getAs[Array[Byte]]("bytes")
            println(s"[Batch $batchId] Segment: $name, Size: ${bytes.length} bytes")

            var localTmpPath: java.nio.file.Path = null
            try {
              localTmpPath = Files.createTempFile(s"seg-${batchId}-", s"-$name.mp4")
              Files.write(localTmpPath, bytes)
              println(s"[Batch $batchId] Segment $name written to temporary file: $localTmpPath")

              val s3TargetPathStr = s"s3a://video-bucket/segments/$name"
              val localHadoopPath = new Path(localTmpPath.toUri)
              val s3TargetPath = new Path(s3TargetPathStr)

              s3fs.copyFromLocalFile(false, true, localHadoopPath, s3TargetPath)
              println(s"[Batch $batchId] Copied $name to S3: $s3TargetPathStr")

              println(s"[Batch $batchId] Attempting to write metadata for $name to YTsaurus...")
              val ytProxySparkConfBatch = batchSparkSession.conf.getOption("spark.yt.proxy").getOrElse("NOT SET IN SPARK_CONF for batch")
              val ytProxyHadoopConfSparkBatch = batchSparkSession.conf.getOption("spark.hadoop.yt.proxy").getOrElse("NOT SET IN SPARK_CONF (for spark.hadoop.yt.proxy) for batch")
              val ytProxyHadoopConfDirectBatch = batchHConf.get("yt.proxy", "NOT SET IN batchHConf (direct) for batch")
              val ytProxySysPropCheckBatch = System.getProperty("yt.proxy", "NOT SET AS SYSPROP for batch")

              println(s"[Batch $batchId] (in-batch) YTsaurus proxy from SparkConf (spark.yt.proxy): $ytProxySparkConfBatch")
              println(s"[Batch $batchId] (in-batch) YTsaurus proxy from SparkConf (spark.hadoop.yt.proxy): $ytProxyHadoopConfSparkBatch")
              println(s"[Batch $batchId] (in-batch) YTsaurus proxy from current batchHConf (yt.proxy): $ytProxyHadoopConfDirectBatch")
              println(s"[Batch $batchId] (in-batch) YTsaurus proxy from System Property: $ytProxySysPropCheckBatch")

              val metaDataSeq = Seq((name, System.currentTimeMillis(), bytes.length))
              val meta = batchSparkSession.createDataFrame(metaDataSeq)
                .toDF("segment_name", "timestamp_ms", "size_bytes")

              println(s"[Batch $batchId] DataFrame to write to YT for $name:")
              // meta.show(false)

              meta.write
                .format("yt")
                .mode("append")
                .option("path", "yt://home/video_metadata") // Используем схему yt://
                .option("ignoreNullable", "true")
                .save()
              println(s"[Batch $batchId] Successfully saved metadata for $name to YTsaurus.")

            } catch {
              case e: Throwable =>
                println(s"[Batch $batchId] ERROR processing segment $name: ${e.getMessage}")
                e.printStackTrace()
            } finally {
              if (localTmpPath != null && Files.exists(localTmpPath)) {
                try {
                  Files.delete(localTmpPath)
                  println(s"[Batch $batchId] Deleted temporary file: $localTmpPath for segment $name")
                } catch {
                  case e: Throwable => println(s"[Batch $batchId] Error deleting temporary file $localTmpPath for segment $name: ${e.getMessage}")
                }
              }
            }
          }
        } else {
          println(s"[Batch $batchId] is empty, no segments to process.")
        }
        println(s"--- Finished processing for batchId: $batchId ---")
      }
      .option("checkpointLocation", "/tmp/spark-checkpoints/video")
      .trigger(org.apache.spark.sql.streaming.Trigger.ProcessingTime("10 seconds"))
      .start()

    println("Spark Streaming query started. Waiting for termination...")
    query.awaitTermination()
    println("Spark Streaming query terminated.")
  }
}