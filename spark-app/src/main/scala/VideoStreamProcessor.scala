import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import java.nio.file.{Files, Paths}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.conf.Configuration

object VideoStreamProcessor {
  def main(args: Array[String]): Unit = {

    val ytProxyValue = "ytsaurus:80"
    // 1. Устанавливаем системное свойство для yt.proxy (это сработало!)
    System.setProperty("yt.proxy", ytProxyValue)
    println(s"""PROGRAMMATICALLY SET System.setProperty("yt.proxy", "$ytProxyValue")""")

    println("Attempting to build SparkSession with YTsaurus configurations...")

    val spark = SparkSession.builder()
      .appName("VideoStreamProcessor")
      .master("local[*]")
      // 2. Передаем конфигурации SPYT через SparkConf
      .config("spark.yt.proxy", ytProxyValue) // Для SPYT connector
      .config("spark.hadoop.yt.proxy", ytProxyValue) // Для Hadoop Configuration внутри Spark

      // 3. ЯВНО УКАЗЫВАЕМ РЕАЛИЗАЦИИ FILESYSTEM ДЛЯ СХЕМЫ "yt" (КЛЮЧЕВОЕ ИЗМЕНЕНИЕ)
      .config("spark.hadoop.fs.yt.impl", "tech.ytsaurus.spyt.fs.YtFileSystem")
      .config("spark.hadoop.fs.AbstractFileSystem.yt.impl", "tech.ytsaurus.spyt.fs.YtAbstractFileSystem")

      .config("spark.yt.read.ignoreNullable", "true")
      .config("spark.yt.write.ignoreNullable", "true")
      .getOrCreate()

    println("SparkSession built successfully.")
    spark.sparkContext.setLogLevel("WARN")

    val globalHConf = spark.sparkContext.hadoopConfiguration

    // ОТЛАДОЧНЫЙ БЛОК: Проверяем, что FileSystem для "yt" теперь находится
    println("---- Attempting to get FileSystem for scheme 'yt' AFTER SparkSession init (from globalHConf) ----")
    try {
      val ytFs = FileSystem.get(new java.net.URI("yt://any_cluster_name_for_test"), globalHConf)
      println(s"Successfully got FS for scheme 'yt': ${ytFs.getScheme}, Class: ${ytFs.getClass.getName}, URI: ${ytFs.getUri}")
    } catch {
      case e: Throwable =>
        println(s"ERROR getting FS for scheme 'yt': ${e.getMessage}")
        e.printStackTrace() // Важно увидеть стек вызовов, если ошибка останется
    }

    println("---- SparkConf effective settings at Driver Start ----")
    spark.conf.getAll.foreach { case (k, v) =>
      if (k.contains("yt.") || k.contains("s3a.") || k.contains("fs.yt.impl")) { // Добавил fs.yt.impl для проверки
        println(s"SparkConf: $k = $v")
      }
    }
    println("---- HadoopConf effective settings at Driver Start (via SparkContext globalHConf) ----")
    println(s"HadoopConf fs.yt.impl (from globalHConf): ${globalHConf.get("fs.yt.impl", "NOT SET")}")
    println(s"HadoopConf fs.AbstractFileSystem.yt.impl (from globalHConf): ${globalHConf.get("fs.AbstractFileSystem.yt.impl", "NOT SET")}")
    println(s"HadoopConf yt.proxy (direct from globalHConf): ${globalHConf.get("yt.proxy", "NOT SET")}")
    println(s"System Property yt.proxy (after programmatic set): ${System.getProperty("yt.proxy", "NOT SET AS SYSPROP")}")


    // ==== S3 (MinIO) конфиги ====
    globalHConf.set("fs.s3a.endpoint", "http://minio:9000")
    globalHConf.set("fs.s3a.access.key", "minioadmin")
    globalHConf.set("fs.s3a.secret.key", "minioadmin")
    globalHConf.set("fs.s3a.path.style.access", "true")
    globalHConf.set("fs.s3a.connection.ssl.enabled", "false")
    globalHConf.set("fs.s3a.attempts.maximum", "3")
    globalHConf.set("fs.s3a.connection.timeout", "15000")
    globalHConf.set("fs.s3a.socket.timeout", "15000")

    // ==== Kafka источник ====
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
              // Отладка: Проверяем, что Hadoop конфигурация внутри батча содержит нужные YT настройки
              println(s"[Batch $batchId] (in-batch) batchHConf fs.yt.impl: ${batchHConf.get("fs.yt.impl", "NOT SET IN BATCH_HCONF")}")
              println(s"[Batch $batchId] (in-batch) batchHConf fs.AbstractFileSystem.yt.impl: ${batchHConf.get("fs.AbstractFileSystem.yt.impl", "NOT SET IN BATCH_HCONF")}")
              println(s"[Batch $batchId] (in-batch) batchHConf yt.proxy: ${batchHConf.get("yt.proxy", "NOT SET IN BATCH_HCONF")}")
              println(s"[Batch $batchId] (in-batch) System Property yt.proxy: ${System.getProperty("yt.proxy", "NOT SET AS SYSPROP for batch")}")


              val metaDataSeq = Seq((name, System.currentTimeMillis(), bytes.length))
              val meta = batchSparkSession.createDataFrame(metaDataSeq)
                .toDF("segment_name", "timestamp_ms", "size_bytes")

              meta.write
                .format("yt")
                .mode("append")
                .option("path", "yt://home/video_metadata") // Используем схему yt://
                .option("ignoreNullable", "true") // Полезно
                .save()
              println(s"[Batch $batchId] Successfully saved metadata for $name to YTsaurus.")

            } catch {
              case e: Throwable =>
                println(s"[Batch $batchId] ERROR processing segment $name: ${e.getMessage}")
                e.printStackTrace()
            } finally {
              if (localTmpPath != null && Files.exists(localTmpPath)) {
                try { Files.delete(localTmpPath) } catch { case e: Throwable => println(s"[Batch $batchId] Error deleting temp file $localTmpPath: ${e.getMessage}") }
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