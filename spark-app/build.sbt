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
      "tech.ytsaurus" %% "spark-yt" % "2.6.3"
    )
  ) 