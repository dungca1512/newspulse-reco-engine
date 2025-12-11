package com.newspulse.trending

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window

/**
 * Spike detection algorithms for trending detection
 */
object SpikeDetector {
  
  /**
   * Detect spikes using z-score
   * A spike is detected when the value is more than threshold standard deviations from the mean
   */
  def detectZScoreSpikes(df: DataFrame, valueCol: String, threshold: Double = 2.0): DataFrame = {
    val statsWindow = Window.partitionBy("cluster_id").orderBy("window")
      .rowsBetween(-12, 0) // Look at last 12 windows (2 hours for 10-min windows)
    
    df
      .withColumn("rolling_mean", avg(col(valueCol)).over(statsWindow))
      .withColumn("rolling_std", stddev(col(valueCol)).over(statsWindow))
      .withColumn(
        "z_score",
        (col(valueCol) - col("rolling_mean")) / (col("rolling_std") + 0.001)
      )
      .withColumn("is_spike", col("z_score") > threshold)
  }
  
  /**
   * Detect spikes using moving average comparison
   */
  def detectMovingAverageSpikes(
    df: DataFrame,
    valueCol: String,
    shortWindow: Int = 3,
    longWindow: Int = 12,
    threshold: Double = 1.5
  ): DataFrame = {
    val shortWindowSpec = Window.partitionBy("cluster_id").orderBy("window")
      .rowsBetween(-shortWindow + 1, 0)
    val longWindowSpec = Window.partitionBy("cluster_id").orderBy("window")
      .rowsBetween(-longWindow + 1, 0)
    
    df
      .withColumn("short_ma", avg(col(valueCol)).over(shortWindowSpec))
      .withColumn("long_ma", avg(col(valueCol)).over(longWindowSpec))
      .withColumn("ma_ratio", col("short_ma") / (col("long_ma") + 0.001))
      .withColumn("is_spike", col("ma_ratio") > threshold)
  }
  
  /**
   * Detect first occurrence of a topic (breaking news)
   */
  def detectFirstOccurrence(df: DataFrame): DataFrame = {
    val firstWindow = Window.partitionBy("cluster_id").orderBy("window")
    
    df
      .withColumn("row_num", row_number().over(firstWindow))
      .withColumn("is_first_occurrence", col("row_num") === 1)
  }
  
  /**
   * Detect rapid growth (velocity spike)
   */
  def detectRapidGrowth(df: DataFrame, growthThreshold: Double = 0.5): DataFrame = {
    val prevWindow = Window.partitionBy("cluster_id").orderBy("window")
    
    df
      .withColumn("prev_count", lag("article_count", 1).over(prevWindow))
      .withColumn(
        "growth_rate",
        (col("article_count") - coalesce(col("prev_count"), lit(0))) /
          (coalesce(col("prev_count"), lit(1)))
      )
      .withColumn("is_rapid_growth", col("growth_rate") > growthThreshold)
  }
}
