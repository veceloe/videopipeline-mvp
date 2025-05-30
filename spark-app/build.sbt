ThisBuild / scalaVersion := "2.12.18"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "videopipeline",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % "3.3.2" % "provided",
      "org.apache.spark" %% "spark-sql"  % "3.3.2" % "provided",
      "org.apache.spark" %% "spark-sql-kafka-0-10" % "3.3.2",
      "org.apache.hadoop" % "hadoop-aws" % "3.3.2",
      "tech.ytsaurus" %% "spark-yt-data-source-base" % "2.6.4",
      "tech.ytsaurus" %% "spark-yt-data-source-extended" % "2.6.4",
      "tech.ytsaurus" %% "spark-yt-spark-adapter-impl-330" % "2.6.4"
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("org", "apache", "spark", "unused", "UnusedStubClass.class") => MergeStrategy.first

      // Аккуратно объединяем содержимое ВСЕХ файлов org.apache.hadoop.fs.FileSystem
      // Это важно для регистрации кастомных файловых систем, таких как ytfs от YTsaurus
      case x if x.endsWith("org.apache.hadoop.fs.FileSystem") =>
        MergeStrategy.concat // Объединяет содержимое файлов. Убедись, что каждый файл содержит строки вида scheme=classname

      // Остальные сервисные файлы тоже объединяем (если есть другие)
      case x if x.startsWith("META-INF/services/") =>
        MergeStrategy.concat // Общая стратегия для всех сервисных файлов

      case PathList("META-INF", xs @ _*) =>
        xs map {_.toLowerCase} match {
          case ("manifest.mf" :: Nil) | ("index.list" :: Nil) | ("dependencies" :: Nil) => MergeStrategy.discard
          case (name :: Nil) if name.endsWith(".sf") || name.endsWith(".dsa") || name.endsWith(".rsa") => MergeStrategy.discard
          case _ => MergeStrategy.first // Для других файлов в META-INF берем первый
        }
      case "reference.conf" | "application.conf" => MergeStrategy.concat

      case PathList("schema", _*) => MergeStrategy.first // Для файлов schema/* (если есть от YTsaurus)

      // Для файлов module-info.class (часто вызывают проблемы с Java 9+)
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard

      // Явное указание для YTsaurus schema файлов, если они есть и вызывают конфликты при MergeStrategy.first
      // case PathList(ps @ _*) if ps.startsWith("tech/ytsaurus/spyt/fs/ схема") => MergeStrategy.first

      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )