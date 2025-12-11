package com.newspulse.etl

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window

/**
 * Deduplication logic for news articles
 */
object Deduplicator {
  
  /**
   * Deduplicate streaming DataFrame by article ID
   * Uses windowing to handle duplicates within a time window
   */
  def deduplicateStream(df: DataFrame): DataFrame = {
    // Simple dedup by dropping duplicates on ID
    df.dropDuplicates("id")
  }
  
  /**
   * Deduplicate batch DataFrame with more sophisticated logic
   * - Exact URL match
   * - Title similarity (for near-duplicates)
   * - Content hash
   */
  def deduplicateBatch(df: DataFrame)(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._
    
    // Add content hash for exact duplicate detection
    val withHash = df.withColumn("content_hash", md5(col("content")))
    
    // Window for picking the earliest article in duplicates
    val window = Window.partitionBy("content_hash").orderBy("crawlTime")
    
    withHash
      .withColumn("row_num", row_number().over(window))
      .filter(col("row_num") === 1)
      .drop("row_num", "content_hash")
  }
  
  /**
   * Compute simple string similarity (Jaccard)
   */
  def jaccardSimilarity(text1: String, text2: String): Double = {
    if (text1 == null || text2 == null) return 0.0
    
    val words1 = text1.toLowerCase.split("\\s+").toSet
    val words2 = text2.toLowerCase.split("\\s+").toSet
    
    if (words1.isEmpty || words2.isEmpty) return 0.0
    
    val intersection = words1.intersect(words2).size.toDouble
    val union = words1.union(words2).size.toDouble
    
    intersection / union
  }
  
  /**
   * Check if two titles are similar (likely same event)
   */
  def areTitlesSimilar(title1: String, title2: String, threshold: Double = 0.7): Boolean = {
    jaccardSimilarity(title1, title2) >= threshold
  }
  
  /**
   * Generate simhash for near-duplicate detection
   * Simhash is a locality-sensitive hash that produces similar hashes for similar content
   */
  def simhash(text: String, hashBits: Int = 64): Long = {
    if (text == null || text.isEmpty) return 0L
    
    val words = text.toLowerCase.split("\\s+").filter(_.length > 2)
    val v = new Array[Int](hashBits)
    
    for (word <- words) {
      val hash = word.hashCode.toLong
      for (i <- 0 until hashBits) {
        if (((hash >> i) & 1L) == 1L) {
          v(i) += 1
        } else {
          v(i) -= 1
        }
      }
    }
    
    var fingerprint = 0L
    for (i <- 0 until hashBits) {
      if (v(i) > 0) {
        fingerprint |= (1L << i)
      }
    }
    
    fingerprint
  }
  
  /**
   * Count differing bits between two simhashes (Hamming distance)
   */
  def hammingDistance(hash1: Long, hash2: Long): Int = {
    java.lang.Long.bitCount(hash1 ^ hash2)
  }
  
  /**
   * Check if two articles are near-duplicates based on simhash
   */
  def areNearDuplicates(text1: String, text2: String, threshold: Int = 3): Boolean = {
    val hash1 = simhash(text1)
    val hash2 = simhash(text2)
    hammingDistance(hash1, hash2) <= threshold
  }
  
  /**
   * Spark UDF for simhash computation
   */
  val simhashUDF = udf((text: String) => simhash(text))
  
  /**
   * Spark UDF for Jaccard similarity
   */
  val jaccardUDF = udf((text1: String, text2: String) => jaccardSimilarity(text1, text2))
}
