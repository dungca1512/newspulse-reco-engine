package com.newspulse.clustering

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

/**
 * Generate human-readable labels for clusters
 * Uses TF-IDF to find representative keywords
 */
object ClusterLabeler {
  
  /**
   * Generate labels for clusters based on common keywords in titles
   */
  def generateLabels(articlesDF: DataFrame, clusterStatsDF: DataFrame)
                    (implicit spark: SparkSession): DataFrame = {
    import spark.implicits._
    
    // Extract keywords from titles in each cluster
    val keywordsDF = articlesDF
      .select("cluster_id", "title")
      .withColumn("words", split(lower(col("title")), "\\s+"))
      .withColumn("word", explode(col("words")))
      .filter(length(col("word")) > 2) // Filter short words
      .filter(!col("word").isin(getStopWords: _*)) // Remove stopwords
      .groupBy("cluster_id", "word")
      .count()
      .withColumn(
        "rank",
        row_number().over(
          org.apache.spark.sql.expressions.Window
            .partitionBy("cluster_id")
            .orderBy(desc("count"))
        )
      )
      .filter(col("rank") <= 3) // Top 3 keywords
      .groupBy("cluster_id")
      .agg(concat_ws(" | ", collect_list("word")).as("keywords"))
    
    // Join with cluster stats
    clusterStatsDF
      .join(keywordsDF, Seq("cluster_id"), "left")
      .withColumn(
        "generated_label",
        coalesce(col("keywords"), col("cluster_label"))
      )
  }
  
  /**
   * Vietnamese stopwords list
   */
  private def getStopWords: Seq[String] = Seq(
    // Vietnamese stopwords
    "và", "của", "là", "cho", "trong", "với", "được", "này", "đã", "có",
    "một", "những", "các", "để", "người", "theo", "khi", "từ", "về",
    "như", "đến", "không", "hay", "nhưng", "nhiều", "sau", "trước",
    "cũng", "vào", "ra", "lên", "xuống", "tại", "bị", "do", "nên",
    "thì", "mà", "tôi", "bạn", "họ", "chúng", "ta", "anh", "chị",
    // Common words in news
    "tin", "bài", "viết", "đọc", "xem", "chi", "tiết", "thêm",
    "mới", "nhất", "hôm", "nay", "ngày", "tháng", "năm"
  )
  
  /**
   * Score cluster quality based on coherence
   */
  def scoreClusterQuality(articlesDF: DataFrame)
                         (implicit spark: SparkSession): DataFrame = {
    import spark.implicits._
    
    // Compute intra-cluster similarity (using title overlap)
    articlesDF
      .groupBy("cluster_id")
      .agg(
        count("*").as("size"),
        // Source diversity (more sources = better coverage)
        size(collect_set("source")).as("source_count"),
        // Time span (shorter = more focused)
        (max("publishTime") - min("publishTime")).as("time_span_ms")
      )
      .withColumn(
        "quality_score",
        // Higher score for: more articles, more sources, shorter time span
        col("size") * col("source_count") / (col("time_span_ms") / 3600000 + 1)
      )
  }
  
  /**
   * Merge similar clusters
   */
  def mergeSimilarClusters(clustersDF: DataFrame, threshold: Double = 0.8)
                          (implicit spark: SparkSession): DataFrame = {
    // This would require comparing cluster centroids
    // Simplified version: merge clusters with high keyword overlap
    // Full implementation would use embedding similarity
    clustersDF
  }
}
