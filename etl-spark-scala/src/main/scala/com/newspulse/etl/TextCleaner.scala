package com.newspulse.etl

import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.udf

import java.text.Normalizer
import scala.util.matching.Regex

/**
 * Text cleaning utilities for Vietnamese news articles
 */
object TextCleaner {

  /**
   * Clean and normalize text content
   */
  def cleanText(text: String): String = {
    if (text == null || text.isEmpty) return ""
    
    var cleaned = text
    
    // Remove HTML tags
    cleaned = cleaned.replaceAll("<[^>]+>", " ")
    
    // Decode HTML entities
    cleaned = decodeHtmlEntities(cleaned)
    
    // Normalize Unicode (NFC normalization for Vietnamese)
    cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFC)
    
    // Remove control characters
    cleaned = cleaned.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "")
    
    // Remove zero-width characters
    cleaned = cleaned.replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
    
    // Normalize whitespace
    cleaned = cleaned.replaceAll("\\s+", " ")
    
    // Remove leading/trailing whitespace
    cleaned = cleaned.trim
    
    // Fix common Vietnamese encoding issues
    cleaned = fixVietnameseEncoding(cleaned)
    
    cleaned
  }
  
  /**
   * Decode common HTML entities
   */
  private def decodeHtmlEntities(text: String): String = {
    text
      .replace("&nbsp;", " ")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&apos;", "'")
      .replace("&#x27;", "'")
      .replace("&ndash;", "–")
      .replace("&mdash;", "—")
      .replace("&hellip;", "...")
      .replace("&rsquo;", "'")
      .replace("&lsquo;", "'")
      .replace("&rdquo;", "\"")
      .replace("&ldquo;", "\"")
  }
  
  /**
   * Fix common Vietnamese encoding issues
   */
  private def fixVietnameseEncoding(text: String): String = {
    // Vietnamese combining characters normalization
    text
      // Fix double-encoded UTF-8
      .replace("Ä'", "đ")
      .replace("Ä", "đ")
      .replace("Æ°", "ư")
      .replace("Æ¡", "ơ")
      // Common mojibake fixes for Vietnamese
  }
  
  /**
   * Remove special formatting characters commonly found in news content
   */
  def removeSpecialFormatting(text: String): String = {
    if (text == null) return ""
    
    text
      .replaceAll("\\[.*?\\]", "") // Remove [brackets]
      .replaceAll("\\{.*?\\}", "") // Remove {braces}
      .replaceAll("\\(Ảnh:.*?\\)", "") // Remove photo credits
      .replaceAll("\\(Nguồn:.*?\\)", "") // Remove source credits
      .replaceAll(">>.*", "") // Remove "read more" markers
      .replaceAll("Xem thêm:.*", "") // Remove Vietnamese "read more"
      .trim
  }
  
  /**
   * Extract sentences from text
   */
  def extractSentences(text: String): Array[String] = {
    if (text == null || text.isEmpty) return Array.empty
    
    // Vietnamese sentence splitter
    text.split("[.!?]+")
      .map(_.trim)
      .filter(_.length > 10)
  }
  
  /**
   * Spark UDF for text cleaning
   */
  val cleanTextUDF: UserDefinedFunction = udf((text: String) => {
    if (text == null) null
    else cleanText(removeSpecialFormatting(text))
  })
  
  /**
   * Spark UDF for sentence extraction
   */
  val extractSentencesUDF: UserDefinedFunction = udf((text: String) => {
    extractSentences(text)
  })
}
