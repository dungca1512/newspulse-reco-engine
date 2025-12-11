ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "com.newspulse"

lazy val root = (project in file("."))
  .settings(
    name := "crawler-scala",
    
    libraryDependencies ++= Seq(
      // HTML Parsing
      "org.jsoup" % "jsoup" % "1.17.2",
      
      // Kafka
      "org.apache.kafka" % "kafka-clients" % "3.6.1",
      
      // JSON
      "com.typesafe.play" %% "play-json" % "2.10.4",
      
      // HTTP Client
      "com.softwaremill.sttp.client3" %% "core" % "3.9.1",
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-future" % "3.9.1",
      
      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      
      // Configuration
      "com.typesafe" % "config" % "1.4.3",
      
      // Scheduling
      "com.github.alonsodomin.cron4s" %% "cron4s-core" % "0.7.0",
      
      // Testing
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    
    // Assembly settings
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case x => MergeStrategy.first
    },
    
    assembly / mainClass := Some("com.newspulse.crawler.NewsCrawler")
  )
