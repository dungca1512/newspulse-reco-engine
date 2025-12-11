package com.newspulse.trending

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.{OutputMode, Trigger}
import org.apache.spark.sql.types._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.delta.tables._

/**
 * Real-time trending detection engine using Spark Structured Streaming
 * Detects spikes in news coverage and calculates trending scores
 */
object TrendingEngine extends App with LazyLogging {
  
  val config = ConfigFactory.load()
  
  // Initialize Spark
  val spark = SparkSession.builder()
    .appName(config.getString("trending.spark.app-name"))
    .master(config.getString("trending.spark.master"))
    .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
    .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
    .getOrCreate()
    
  import spark.implicits._
  
  logger.info("Starting Trending Engine...")
  
  // Article schema
  val articleSchema = StructType(Seq(
    StructField("id", StringType),
    StructField("url", StringType),
    StructField("title", StringType),
    StructField("description", StringType),
    StructField("content", StringType),
    StructField("author", StringType),
    StructField("source", StringType),
    StructField("category", StringType),
    StructField("tags", ArrayType(StringType)),
    StructField("imageUrl", StringType),
    StructField("publishTime", LongType),
    StructField("crawlTime", LongType),
    StructField("cluster_id", IntegerType),
    StructField("embedding", ArrayType(DoubleType))
  ))
  
  /**
   * Read streaming data from Kafka (with cluster assignments)
   */
  def readStream(): DataFrame = {
    spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", config.getString("trending.kafka.bootstrap-servers"))
      .option("subscribe", config.getString("trending.kafka.input-topic"))
      .option("startingOffsets", "latest")
      .load()
      .selectExpr("CAST(value AS STRING) as json", "timestamp")
      .select(
        from_json(col("json"), articleSchema).as("article"),
        col("timestamp").as("event_time")
      )
      .select("article.*", "event_time")
      .withWatermark("event_time", "10 minutes")
  }
  
  /**
   * Calculate trending scores using windowed aggregation
   */
  def calculateTrendingScores(df: DataFrame): DataFrame = {
    val freqWeight = config.getDouble("trending.detection.weights.frequency")
    val velWeight = config.getDouble("trending.detection.weights.velocity")
    val divWeight = config.getDouble("trending.detection.weights.source-diversity")
    val minArticles = config.getInt("trending.detection.min-articles")
    val minSources = config.getInt("trending.detection.min-sources")
    
    // Aggregate by cluster and time window
    df.groupBy(
        window(col("event_time"), "10 minutes", "1 minute"),
        col("cluster_id")
      )
      .agg(
        count("*").as("article_count"),
        countDistinct("source").as("source_count"),
        collect_list("title").as("titles"),
        collect_set("source").as("sources"),
        first("category").as("category"),
        min("publishTime").as("earliest_publish"),
        max("publishTime").as("latest_publish"),
        first("imageUrl").as("image_url")
      )
      .filter(col("article_count") >= minArticles)
      .filter(col("source_count") >= minSources)
      // Calculate velocity (articles per minute)
      .withColumn("velocity", col("article_count") / 10.0)
      // Calculate trending score
      .withColumn(
        "trending_score",
        (col("article_count") * freqWeight) +
        (col("velocity") * 100 * velWeight) +
        (col("source_count") * 10 * divWeight)
      )
      // Get representative title
      .withColumn("title", element_at(col("titles"), 1))
      // Add ranking
      .withColumn(
        "rank",
        row_number().over(
          org.apache.spark.sql.expressions.Window
            .partitionBy(col("window"))
            .orderBy(desc("trending_score"))
        )
      )
  }
  
  /**
   * Detect breaking news (sudden spike)
   */
  def detectBreakingNews(currentWindow: DataFrame, previousWindow: DataFrame): DataFrame = {
    // Compare article count between windows
    currentWindow.as("curr")
      .join(
        previousWindow.as("prev"),
        col("curr.cluster_id") === col("prev.cluster_id"),
        "left"
      )
      .withColumn(
        "velocity_change",
        (col("curr.article_count") - coalesce(col("prev.article_count"), lit(0))) /
          (coalesce(col("prev.article_count"), lit(1)) + 1)
      )
      .filter(col("velocity_change") > 2.0) // 200% increase
      .withColumn("is_breaking", lit(true))
  }
  
  /**
   * Run the streaming trending detection
   */
  def runStreaming(): Unit = {
    val stream = readStream()
    val trending = calculateTrendingScores(stream)
    
    // Write to Kafka
    val kafkaQuery = trending
      .select(
        col("cluster_id").cast("string").as("key"),
        to_json(struct(col("*"))).as("value")
      )
      .writeStream
      .format("kafka")
      .option("kafka.bootstrap.servers", config.getString("trending.kafka.bootstrap-servers"))
      .option("topic", config.getString("trending.kafka.output-topic"))
      .option("checkpointLocation", config.getString("trending.spark.checkpoint-location") + "/kafka")
      .outputMode(OutputMode.Update())
      .trigger(Trigger.ProcessingTime("1 minute"))
      .start()
    
    // Write to Delta Lake
    val deltaQuery = trending
      .writeStream
      .format("delta")
      .option("checkpointLocation", config.getString("trending.spark.checkpoint-location") + "/delta")
      .option("path", config.getString("trending.delta.trending-path"))
      .outputMode(OutputMode.Append())
      .trigger(Trigger.ProcessingTime("1 minute"))
      .start()
    
    logger.info("Trending engine started. Waiting for data...")
    
    spark.streams.awaitAnyTermination()
  }
  
  /**
   * Run batch trending calculation
   */
  def runBatch(): Unit = {
    logger.info("Running batch trending calculation...")
    
    // Load recent articles with clusters
    val clusterPath = config.getString("trending.delta.cluster-path")
    val articles = spark.read.format("delta").load(s"$clusterPath/articles")
    
    // Add event_time from crawl time
    val withTime = articles
      .withColumn("event_time", from_unixtime(col("crawlTime") / 1000).cast("timestamp"))
    
    val trending = calculateTrendingScores(withTime)
    
    // Get top N trending
    val topN = config.getInt("trending.output.top-n")
    val topTrending = trending
      .filter(col("rank") <= topN)
      .orderBy(desc("trending_score"))
    
    topTrending.show(20, truncate = false)
    
    // Save to Delta
    topTrending
      .write
      .format("delta")
      .mode("overwrite")
      .save(config.getString("trending.delta.trending-path"))
    
    logger.info(s"Saved top $topN trending topics")
  }
  
  // Parse command line arguments
  args.toList match {
    case "--stream" :: Nil => runStreaming()
    case "--batch" :: Nil => runBatch()
    case _ => runBatch() // Default to batch
  }
}
