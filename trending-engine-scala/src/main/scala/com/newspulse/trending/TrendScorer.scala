package com.newspulse.trending

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

/**
 * Trending score calculation with multiple factors
 */
object TrendScorer {
  
  /**
   * Calculate comprehensive trending score
   * 
   * Score = α * frequency_score + β * velocity_score + γ * diversity_score + δ * recency_score
   */
  def calculateTrendScore(
    df: DataFrame,
    freqWeight: Double = 0.25,
    velWeight: Double = 0.25,
    divWeight: Double = 0.25,
    recencyWeight: Double = 0.25
  ): DataFrame = {
    
    // Normalize frequency (article count)
    val maxCount = df.select(max("article_count")).first().getLong(0).toDouble
    val normalizedFreq = if (maxCount > 0) maxCount else 1.0
    
    df
      // Frequency score (normalized)
      .withColumn("frequency_score", col("article_count") / normalizedFreq * 100)
      
      // Velocity score (articles per minute, normalized)
      .withColumn("velocity_score", col("velocity") * 10)
      
      // Source diversity score (more sources = higher score)
      .withColumn("diversity_score", col("source_count") * 15)
      
      // Recency score (exponential decay based on time)
      .withColumn(
        "recency_score",
        when(col("latest_publish").isNotNull,
          pow(0.95, (current_timestamp().cast("long") - col("latest_publish") / 1000) / 3600)
        ).otherwise(0.5) * 100
      )
      
      // Combined trending score
      .withColumn(
        "trending_score",
        (col("frequency_score") * freqWeight) +
        (col("velocity_score") * velWeight) +
        (col("diversity_score") * divWeight) +
        (col("recency_score") * recencyWeight)
      )
  }
  
  /**
   * Apply time decay to scores
   * Score decreases exponentially over time
   */
  def applyTimeDecay(df: DataFrame, decayFactor: Double = 0.9, hoursCol: String = "hours_old"): DataFrame = {
    df
      .withColumn(
        "hours_old",
        (current_timestamp().cast("long") - col("latest_publish") / 1000) / 3600
      )
      .withColumn(
        "decayed_score",
        col("trending_score") * pow(decayFactor, col("hours_old"))
      )
  }
  
  /**
   * Boost score for breaking news
   */
  def boostBreakingNews(df: DataFrame, boostFactor: Double = 2.0): DataFrame = {
    df.withColumn(
      "trending_score",
      when(col("is_spike"), col("trending_score") * boostFactor)
        .otherwise(col("trending_score"))
    )
  }
  
  /**
   * Penalize low-quality clusters
   */
  def penalizeLowQuality(df: DataFrame): DataFrame = {
    df.withColumn(
      "trending_score",
      when(col("source_count") < 2, col("trending_score") * 0.5)
        .when(col("article_count") < 3, col("trending_score") * 0.7)
        .otherwise(col("trending_score"))
    )
  }
  
  /**
   * Get top trending topics
   */
  def getTopTrending(df: DataFrame, n: Int = 20): DataFrame = {
    df
      .orderBy(desc("trending_score"))
      .limit(n)
      .withColumn("rank", monotonically_increasing_id() + 1)
  }
  
  /**
   * Calculate category-level trending
   */
  def getCategoryTrending(df: DataFrame, topPerCategory: Int = 5): DataFrame = {
    val categoryWindow = org.apache.spark.sql.expressions.Window
      .partitionBy("category")
      .orderBy(desc("trending_score"))
    
    df
      .withColumn("category_rank", row_number().over(categoryWindow))
      .filter(col("category_rank") <= topPerCategory)
  }
}
