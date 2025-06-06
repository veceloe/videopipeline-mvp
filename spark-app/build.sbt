ThisBuild / scalaVersion := "2.12.18"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "videopipeline",
    libraryDependencies ++= {
      val sparkVersion = "3.3.2"
      val spytVersion = "2.6.3"
      Seq(
        "org.apache.spark" %% "spark-core" % sparkVersion % Provided,
        "org.apache.spark" %% "spark-sql"  % sparkVersion % Provided,
        "org.apache.spark" %% "spark-sql-kafka-0-10" % sparkVersion,
        "org.apache.hadoop" % "hadoop-aws" % "3.3.2",
        "tech.ytsaurus" %% "spark-yt-data-source-base" % spytVersion,
        "tech.ytsaurus" %% "spark-yt-data-source-extended" % spytVersion,
        "tech.ytsaurus" %% "spark-yt-spark-adapter-impl-330" % spytVersion,
        "tech.ytsaurus" % "ytsaurus-client" % "1.2.9"
      )
    },
    assembly / assemblyMergeStrategy := {
      case PathList("org", "apache", "spark", "unused", "UnusedStubClass.class") => MergeStrategy.first
      case x if x.equals("META-INF/services/org.apache.hadoop.fs.FileSystem") =>
        MergeStrategy.filterDistinctLines
      case x if x.startsWith("META-INF/services/") =>
        MergeStrategy.concat
      case PathList("META-INF", xs @ _*) =>
        xs map {_.toLowerCase} match {
          case ("manifest.mf" :: Nil) | ("index.list" :: Nil) | ("dependencies" :: Nil) => MergeStrategy.discard
          case (name :: Nil) if name.endsWith(".sf") || name.endsWith(".dsa") || name.endsWith(".rsa") => MergeStrategy.discard
          case _ => MergeStrategy.first
        }
      case "reference.conf" | "application.conf" => MergeStrategy.concat
      case PathList("schema", _*) => MergeStrategy.first
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case x if x.toLowerCase.startsWith("meta-inf/license") => MergeStrategy.first
      case x if x.toLowerCase.startsWith("meta-inf/notice") => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )