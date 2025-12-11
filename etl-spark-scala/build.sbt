ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.newspulse"

lazy val root = (project in file("."))
  .settings(
    name := "etl-spark-scala",
    
    fork := true,
    javaOptions ++= Seq(
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    ),

    libraryDependencies ++= Seq(
      // Spark
      "org.apache.spark" %% "spark-core" % "3.5.0",
      "org.apache.spark" %% "spark-sql" % "3.5.0",
      "org.apache.spark" %% "spark-streaming" % "3.5.0",
      
      // Spark Kafka
      "org.apache.spark" %% "spark-sql-kafka-0-10" % "3.5.0",
      
      // Delta Lake
      "io.delta" %% "delta-spark" % "3.0.0",
      
      // JSON
      "com.google.code.gson" % "gson" % "2.10.1",
      
      // Language Detection
      "com.optimaize.languagedetector" % "language-detector" % "0.6",
      
      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      
      // Configuration
      "com.typesafe" % "config" % "1.4.3",
      
      // Testing
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    
    // Assembly settings
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case x => MergeStrategy.first
    },
    
    assembly / mainClass := Some("com.newspulse.etl.NewsETL")
  )
