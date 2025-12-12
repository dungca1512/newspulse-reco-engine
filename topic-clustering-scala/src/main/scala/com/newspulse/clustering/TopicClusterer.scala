package com.newspulse.clustering

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.ml.clustering.{KMeans, BisectingKMeans}
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.linalg.{Vector, Vectors}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.delta.tables._

/**
 * Topic clustering using embeddings
 * Groups news articles about the same event/topic together
 */
object TopicClusterer extends App with LazyLogging {
  
  val config = ConfigFactory.load()
  
  // Initialize Spark
  val spark = SparkSession.builder()
    .appName(config.getString("clustering.spark.app-name"))
    .master(config.getString("clustering.spark.master"))
    .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
    .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
    .getOrCreate()
    
  import spark.implicits._
  
  logger.info("Starting Topic Clustering...")
  
  /**
   * Load articles with embeddings from Delta Lake
   */
  def loadArticles(): Option[DataFrame] = {
    val embeddingPath = config.getString("clustering.delta.embedding-path")
    val timeWindowHours = config.getInt("clustering.time-window-hours")
    
    val cutoffTime = System.currentTimeMillis() - (timeWindowHours * 3600 * 1000L)
    
    // Check if Delta table exists
    val deltaLogPath = new java.io.File(embeddingPath, "_delta_log")
    if (!deltaLogPath.exists()) {
      logger.warn(s"Delta table not found at $embeddingPath. Please run the ETL/embedding pipeline first to generate data.")
      return None
    }
    
    try {
      Some(
        spark.read
          .format("delta")
          .load(embeddingPath)
          .filter(col("crawlTime") > cutoffTime)
          .filter(col("embedding").isNotNull)
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load Delta table: ${e.getMessage}")
        None
    }
  }
  
  /**
   * Convert embedding array to ML Vector
   */
  def prepareFeatures(df: DataFrame): DataFrame = {
    // UDF to convert array to vector
    val arrayToVector = udf((arr: Seq[Double]) => {
      Vectors.dense(arr.toArray)
    })
    
    df.withColumn("features", arrayToVector(col("embedding")))
  }
  
  /**
   * Determine optimal number of clusters using elbow method
   */
  def findOptimalK(df: DataFrame, maxK: Int = 50): Int = {
    logger.info("Finding optimal number of clusters...")
    
    val costs = (2 to maxK).map { k =>
      val kmeans = new KMeans()
        .setK(k)
        .setSeed(42)
        .setFeaturesCol("features")
        .setPredictionCol("cluster")
      
      val model = kmeans.fit(df)
      (k, model.summary.trainingCost)
    }
    
    // Find elbow point (where improvement diminishes)
    val improvements = costs.sliding(2).map {
      case Seq((k1, c1), (k2, c2)) => (k2, (c1 - c2) / c1)
    }.toSeq
    
    val optimalK = improvements
      .find(_._2 < 0.1) // Less than 10% improvement
      .map(_._1)
      .getOrElse(10)
    
    logger.info(s"Optimal K: $optimalK")
    optimalK
  }
  
  /**
   * Perform clustering using Bisecting KMeans
   */
  def clusterArticles(df: DataFrame): DataFrame = {
    val algorithm = config.getString("clustering.algorithm.type")
    val configuredK = config.getInt("clustering.algorithm.num-clusters")
    
    val k = if (configuredK < 0) {
      // Auto-detect based on data size
      val count = df.count()
      math.max(5, math.min(100, (count / 10).toInt))
    } else {
      configuredK
    }
    
    logger.info(s"Clustering with $algorithm, k=$k")
    
    val model = algorithm match {
      case "kmeans" =>
        new KMeans()
          .setK(k)
          .setSeed(42)
          .setFeaturesCol("features")
          .setPredictionCol("cluster_id")
          .fit(df)
          
      case "bisecting-kmeans" =>
        new BisectingKMeans()
          .setK(k)
          .setSeed(42)
          .setFeaturesCol("features")
          .setPredictionCol("cluster_id")
          .fit(df)
          
      case _ =>
        throw new IllegalArgumentException(s"Unknown algorithm: $algorithm")
    }
    
    model.transform(df)
  }
  
  /**
   * Compute cluster statistics and labels
   */
  def computeClusterStats(df: DataFrame): DataFrame = {
    // Group by cluster and compute statistics
    df.groupBy("cluster_id")
      .agg(
        count("*").as("cluster_size"),
        collect_list("title").as("titles"),
        collect_list("source").as("sources"),
        min("publishTime").as("earliest_publish"),
        max("publishTime").as("latest_publish"),
        avg("word_count").as("avg_word_count")
      )
      .withColumn("source_diversity", size(array_distinct(col("sources"))))
      .withColumn("cluster_label", element_at(col("titles"), 1)) // Use first title as label
  }
  
  /**
   * Save clustering results
   */
  def saveResults(articlesWithClusters: DataFrame, clusterStats: DataFrame): Unit = {
    val clusterPath = config.getString("clustering.delta.cluster-path")
    
    // Save articles with cluster assignments
    articlesWithClusters
      .drop("features", "embedding")
      .write
      .format("delta")
      .mode("overwrite")
      .save(s"$clusterPath/articles")
    
    // Save cluster statistics
    clusterStats
      .write
      .format("delta")
      .mode("overwrite")
      .save(s"$clusterPath/stats")
    
    logger.info(s"Results saved to $clusterPath")
  }
  
  /**
   * Main clustering pipeline
   */
  def run(): Unit = {
    // Load articles
    loadArticles() match {
      case None =>
        logger.warn("No data available for clustering. Exiting.")
        return
      case Some(articles) =>
        val count = articles.count()
        logger.info(s"Loaded $count articles for clustering")
        
        if (count < 10) {
          logger.warn("Not enough articles for meaningful clustering")
          return
        }
        
        // Prepare features
        val prepared = prepareFeatures(articles)
        
        // Perform clustering
        val clustered = clusterArticles(prepared)
        
        // Compute cluster statistics
        val stats = computeClusterStats(clustered)
        
        // Log summary
        val numClusters = stats.count()
        logger.info(s"Created $numClusters clusters")
        
        // Save results
        saveResults(clustered, stats)
    }
  }
  
  // Run the clustering
  run()
  
  spark.stop()
}
