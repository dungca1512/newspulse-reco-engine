ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "com.newspulse"

lazy val root = (project in file("."))
  .settings(
    name := "trending-engine-scala",
    
    libraryDependencies ++= Seq(
      // Spark - Removed "provided" to allow running locally with 'sbt run'
      "org.apache.spark" %% "spark-core" % "3.5.0",
      "org.apache.spark" %% "spark-sql" % "3.5.0",
      "org.apache.spark" %% "spark-streaming" % "3.5.0",
      
      // Kafka
      "org.apache.spark" %% "spark-sql-kafka-0-10" % "3.5.0",
      
      // Delta Lake
      "io.delta" %% "delta-spark" % "3.0.0",
      
      // Elasticsearch
      "org.elasticsearch" % "elasticsearch-spark-30_2.13" % "8.11.0",
      
      // JSON
      "com.google.code.gson" % "gson" % "2.10.1",
      
      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "org.apache.logging.log4j" % "log4j-core" % "2.22.1",

      // Configuration
      "com.typesafe" % "config" % "1.4.3",
      
      // Testing
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case x => MergeStrategy.first
    },
    
    assembly / mainClass := Some("com.newspulse.trending.TrendingEngine"),

    // Required for Java 17+ compatibility with Spark
    fork := true,
    javaOptions ++= Seq(
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.net=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
      "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
      "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
      "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
      "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
    )
  )
