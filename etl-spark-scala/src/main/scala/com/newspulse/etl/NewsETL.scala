package com.newspulse.etl

import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SparkSession}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.delta.tables._

/**
 * Main ETL job using Spark Structured Streaming
 * Reads from Kafka, cleans text, and writes to Delta Lake and Kafka
 */
object NewsETL extends App with LazyLogging {

  val config = ConfigFactory.load()
  
  // Initialize Spark
  val spark = SparkSession.builder()
    .appName(config.getString("etl.spark.app-name"))
    .master(config.getString("etl.spark.master"))
    .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
    .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
    .getOrCreate()
  
  import spark.implicits._
  
  logger.info("Starting NewsPulse ETL Pipeline...")
  
  // Schema for incoming articles
  val articleSchema = StructType(Seq(
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
    StructField("crawlTime", LongType, nullable = false)
  ))
  
  // Read from Kafka
  val kafkaDF = spark.readStream
    .format("kafka")
    .option("kafka.bootstrap.servers", config.getString("etl.kafka.bootstrap-servers"))
    .option("subscribe", config.getString("etl.kafka.input-topic"))
    .option("startingOffsets", config.getString("etl.kafka.starting-offsets"))
    .load()
  
  // Parse JSON
  val parsedDF = kafkaDF
    .selectExpr("CAST(value AS STRING) as json")
    .select(from_json(col("json"), articleSchema).as("article"))
    .select("article.*")
  
  // Apply text cleaning
  val cleanedDF = parsedDF
    .withColumn("title", TextCleaner.cleanTextUDF(col("title")))
    .withColumn("content", TextCleaner.cleanTextUDF(col("content")))
    .withColumn("description", TextCleaner.cleanTextUDF(col("description")))
    .filter(length(col("content")) >= config.getInt("etl.cleaning.min-content-length"))
    .filter(length(col("content")) <= config.getInt("etl.cleaning.max-content-length"))
    .withColumn("language", LanguageDetector.detectLanguageUDF(col("content")))
    .withColumn("word_count", size(split(col("content"), "\\s+")))
    .withColumn("process_time", current_timestamp())
  
  // Apply deduplication
  val dedupedDF = Deduplicator.deduplicateStream(cleanedDF)
  
  // Write to Delta Lake
  val deltaQuery = dedupedDF.writeStream
    .format("delta")
    .outputMode("append")
    .option("checkpointLocation", config.getString("etl.spark.checkpoint-location") + "/delta")
    .option("path", config.getString("etl.delta.clean-path"))
    .trigger(org.apache.spark.sql.streaming.Trigger.ProcessingTime(
      config.getString("etl.spark.trigger-interval")
    ))
    .start()
  
  // Write to Kafka (cleaned topic)
  val kafkaQuery = dedupedDF
    .select(
      col("id").as("key"),
      to_json(struct(col("*"))).as("value")
    )
    .writeStream
    .format("kafka")
    .option("kafka.bootstrap.servers", config.getString("etl.kafka.bootstrap-servers"))
    .option("topic", config.getString("etl.kafka.output-topic"))
    .option("checkpointLocation", config.getString("etl.spark.checkpoint-location") + "/kafka")
    .trigger(org.apache.spark.sql.streaming.Trigger.ProcessingTime(
      config.getString("etl.spark.trigger-interval")
    ))
    .start()
  
  logger.info("ETL Pipeline started. Waiting for termination...")
  
  // Wait for termination
  spark.streams.awaitAnyTermination()
}
