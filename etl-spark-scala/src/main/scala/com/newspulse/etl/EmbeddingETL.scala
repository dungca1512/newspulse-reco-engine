package com.newspulse.etl

import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SparkSession}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

/**
 * ETL job to read articles with embeddings from Kafka and write to Delta Lake
 * This bridges the gap between the Python Embedding Service and Spark-based clustering
 */
object EmbeddingETL extends App with LazyLogging {

  val config = ConfigFactory.load()

  // Initialize Spark
  val spark = SparkSession.builder()
    .appName("NewsPulse-EmbeddingETL")
    .master(config.getString("etl.spark.master"))
    // Cấu hình giới hạn bộ nhớ cho Driver (quan trọng khi chạy trong Docker)
    .config("spark.driver.memory", if (config.hasPath("etl.spark.driver-memory")) config.getString("etl.spark.driver-memory") else "1g")
    // Giảm bộ nhớ cho UI vì trong Docker thường không xem UI nhiều
    .config("spark.ui.enabled", "false")
    // Cấu hình Delta Lake & Catalog
    .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
    .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
    .getOrCreate()

  import spark.implicits._

  logger.info("Starting Embedding ETL Pipeline...")

  // Schema for articles with embeddings from Python service
  val embeddingSchema = StructType(Seq(
    StructField("id", StringType, nullable = false),
    StructField("url", StringType, nullable = false),
    StructField("title", StringType, nullable = false),
    StructField("description", StringType, nullable = true),
    StructField("content", StringType, nullable = false),
    StructField("author", StringType, nullable = true),
    StructField("source", StringType, nullable = false),
    StructField("category", StringType, nullable = true),
    StructField("tags", ArrayType(StringType), nullable = true),
    StructField("imageUrl", StringType, nullable = true),
    StructField("publishTime", LongType, nullable = true),
    StructField("crawlTime", LongType, nullable = false),
    StructField("language", StringType, nullable = true),
    StructField("word_count", IntegerType, nullable = true),
    StructField("embedding", ArrayType(DoubleType), nullable = true),
    StructField("embedding_model", StringType, nullable = true),
    StructField("embedding_time", StringType, nullable = true)
  ))

  // Read from Kafka (news_embedding topic)
  val kafkaDF = spark.readStream
    .format("kafka")
    .option("kafka.bootstrap.servers", config.getString("etl.kafka.bootstrap-servers"))
    .option("subscribe", config.getString("etl.kafka.embedding-topic"))
    .option("startingOffsets", "latest")
    .load()

  // Parse JSON
  val parsedDF = kafkaDF
    .selectExpr("CAST(value AS STRING) as json")
    .select(from_json(col("json"), embeddingSchema).as("article"))
    .select("article.*")
    .filter(col("embedding").isNotNull)  // Only process articles with valid embeddings

  // Write to Delta Lake
  val deltaQuery = parsedDF.writeStream
    .format("delta")
    .outputMode("append")
    .option("checkpointLocation", config.getString("etl.spark.checkpoint-location") + "/embedding")
    .option("path", config.getString("etl.delta.embedding-path"))
    .option("mergeSchema", "true")
    .trigger(org.apache.spark.sql.streaming.Trigger.ProcessingTime("10 seconds"))
    .start()

  logger.info(s"Embedding ETL Pipeline started. Reading from topic: ${config.getString("etl.kafka.embedding-topic")}")
  logger.info(s"Writing to Delta Lake: ${config.getString("etl.delta.embedding-path")}")

  // Wait for termination
  spark.streams.awaitAnyTermination()
}