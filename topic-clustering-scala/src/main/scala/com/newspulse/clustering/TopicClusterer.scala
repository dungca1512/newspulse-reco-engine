package com.newspulse.clustering

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.ml.clustering.{KMeans, BisectingKMeans}
import org.apache.spark.ml.feature.{HashingTF, IDF, Tokenizer, StopWordsRemover}
import org.apache.spark.ml.linalg.{Vector, Vectors}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.delta.tables._

/**
 * Topic clustering using embeddings or TF-IDF fallback
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
  def loadArticlesWithEmbeddings(): Option[DataFrame] = {
    val embeddingPath = config.getString("clustering.delta.embedding-path")
    val timeWindowHours = config.getInt("clustering.time-window-hours")
    val cutoffTime = System.currentTimeMillis() - (timeWindowHours * 3600 * 1000L)

    val deltaLogPath = new java.io.File(embeddingPath, "_delta_log")
    if (!deltaLogPath.exists()) {
      logger.info(s"Embedding table not found at $embeddingPath")
      return None
    }

    try {
      val df = spark.read
        .format("delta")
        .load(embeddingPath)
        .filter(col("crawlTime") > cutoffTime)
        .filter(col("embedding").isNotNull)

      if (df.isEmpty) {
        logger.info("Embedding table is empty")
        None
      } else {
        Some(df)
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to load embedding table: ${e.getMessage}")
        None
    }
  }

  /**
   * Load clean articles as fallback (without embeddings)
   */
  def loadCleanArticles(): Option[DataFrame] = {
    // Try multiple possible paths for clean data
    val possiblePaths = Seq(
      "../data/lake/clean",
      "./data/lake/clean",
      "data/lake/clean"
    )

    val timeWindowHours = config.getInt("clustering.time-window-hours")
    val cutoffTime = System.currentTimeMillis() - (timeWindowHours * 3600 * 1000L)

    for (path <- possiblePaths) {
      val deltaLogPath = new java.io.File(path, "_delta_log")
      if (deltaLogPath.exists()) {
        try {
          val df = spark.read
            .format("delta")
            .load(path)
            .filter(col("crawlTime") > cutoffTime)

          val count = df.count()
          if (count > 0) {
            logger.info(s"Loaded $count articles from clean data at $path")
            return Some(df)
          }
        } catch {
          case e: Exception =>
            logger.debug(s"Failed to load from $path: ${e.getMessage}")
        }
      }
    }
    None
  }

  /**
   * Convert embedding array to ML Vector
   */
  def prepareEmbeddingFeatures(df: DataFrame): DataFrame = {
    val arrayToVector = udf((arr: Seq[Double]) => {
      Vectors.dense(arr.toArray)
    })
    df.withColumn("features", arrayToVector(col("embedding")))
  }

  /**
   * Create TF-IDF features from text (fallback when no embeddings)
   */
  def prepareTfIdfFeatures(df: DataFrame): DataFrame = {
    logger.info("Using TF-IDF features (fallback mode)")

    // Combine title and content for feature extraction
    val withText = df.withColumn("text", concat_ws(" ", col("title"), col("content")))

    // Tokenize
    val tokenizer = new Tokenizer()
      .setInputCol("text")
      .setOutputCol("words")

    val tokenized = tokenizer.transform(withText)

    // Remove stop words (Vietnamese + English common words)
    val remover = new StopWordsRemover()
      .setInputCol("words")
      .setOutputCol("filtered_words")

    val filtered = remover.transform(tokenized)

    // Hash TF
    val hashingTF = new HashingTF()
      .setInputCol("filtered_words")
      .setOutputCol("raw_features")
      .setNumFeatures(1000)

    val featurized = hashingTF.transform(filtered)

    // IDF
    val idf = new IDF()
      .setInputCol("raw_features")
      .setOutputCol("features")

    val idfModel = idf.fit(featurized)
    idfModel.transform(featurized)
      .drop("text", "words", "filtered_words", "raw_features")
  }

  /**
   * Perform clustering
   */
  def clusterArticles(df: DataFrame): DataFrame = {
    val algorithm = config.getString("clustering.algorithm.type")
    val configuredK = config.getInt("clustering.algorithm.num-clusters")

    val count = df.count()
    val k = if (configuredK < 0) {
      math.max(3, math.min(50, (count / 5).toInt))
    } else {
      math.min(configuredK, count.toInt - 1)
    }

    logger.info(s"Clustering $count articles with $algorithm, k=$k")

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
        new BisectingKMeans()
          .setK(k)
          .setSeed(42)
          .setFeaturesCol("features")
          .setPredictionCol("cluster_id")
          .fit(df)
    }

    model.transform(df)
  }

  /**
   * Compute cluster statistics
   */
  def computeClusterStats(df: DataFrame): DataFrame = {
    val hasWordCount = df.columns.contains("word_count")

    val baseAgg = df.groupBy("cluster_id")
      .agg(
        count("*").as("cluster_size"),
        collect_list("title").as("titles"),
        collect_list("source").as("sources"),
        min("publishTime").as("earliest_publish"),
        max("publishTime").as("latest_publish")
      )

    baseAgg
      .withColumn("source_diversity", size(array_distinct(col("sources"))))
      .withColumn("cluster_label", element_at(col("titles"), 1))
  }

  /**
   * Save results
   */
  def saveResults(articlesWithClusters: DataFrame, clusterStats: DataFrame): Unit = {
    val clusterPath = config.getString("clustering.delta.cluster-path")

    // Columns to drop (may or may not exist)
    val columnsToDrop = Seq("features", "embedding", "text", "words", "filtered_words", "raw_features")
      .filter(c => articlesWithClusters.columns.contains(c))

    articlesWithClusters
      .drop(columnsToDrop: _*)
      .write
      .format("delta")
      .mode("overwrite")
      .save(s"$clusterPath/articles")

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
    // Try to load embedding data first, fall back to clean data
    val (articles, useEmbeddings) = loadArticlesWithEmbeddings() match {
      case Some(df) =>
        logger.info("Using embedding-based clustering")
        (Some(df), true)
      case None =>
        logger.info("Falling back to TF-IDF clustering")
        (loadCleanArticles(), false)
    }

    articles match {
      case None =>
        logger.error("No data available for clustering. Please run the data pipeline first.")
        logger.error("Required: Crawler -> ETL -> (optionally) Embedding Service")
        return

      case Some(df) =>
        val count = df.count()
        logger.info(s"Loaded $count articles for clustering")

        if (count < 3) {
          logger.warn(s"Not enough articles ($count) for meaningful clustering. Need at least 3.")
          return
        }

        // Prepare features
        val prepared = if (useEmbeddings) {
          prepareEmbeddingFeatures(df)
        } else {
          prepareTfIdfFeatures(df)
        }

        // Perform clustering
        val clustered = clusterArticles(prepared)

        // Compute statistics
        val stats = computeClusterStats(clustered)

        val numClusters = stats.count()
        logger.info(s"Created $numClusters clusters")

        // Show sample
        stats.select("cluster_id", "cluster_size", "source_diversity", "cluster_label")
          .orderBy(desc("cluster_size"))
          .show(10, truncate = 50)

        // Save results
        saveResults(clustered, stats)
    }
  }

  // Run
  run()
  spark.stop()
}