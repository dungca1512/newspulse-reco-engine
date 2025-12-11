package com.newspulse.etl

import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.udf

import com.optimaize.langdetect.LanguageDetectorBuilder
import com.optimaize.langdetect.ngram.NgramExtractors
import com.optimaize.langdetect.profiles.LanguageProfileReader
import com.optimaize.langdetect.text.CommonTextObjectFactories

import scala.util.Try

/**
 * Language detection for articles
 */
object LanguageDetector {
  
  // Initialize language detector (lazy to avoid serialization issues)
  @transient private lazy val detector = {
    val languageProfiles = new LanguageProfileReader().readAllBuiltIn()
    LanguageDetectorBuilder.create(NgramExtractors.standard())
      .withProfiles(languageProfiles)
      .build()
  }
  
  @transient private lazy val textObjectFactory = 
    CommonTextObjectFactories.forDetectingOnLargeText()
  
  /**
   * Detect language of text
   * Returns ISO 639-1 language code (e.g., "vi", "en")
   */
  def detectLanguage(text: String): String = {
    if (text == null || text.length < 20) return "unknown"
    
    Try {
      val textObject = textObjectFactory.forText(text.take(1000))
      val result = detector.detect(textObject)
      
      if (result.isPresent) {
        result.get().getLanguage
      } else {
        "unknown"
      }
    }.getOrElse("unknown")
  }
  
  /**
   * Check if text is Vietnamese
   */
  def isVietnamese(text: String): Boolean = {
    detectLanguage(text) == "vi"
  }
  
  /**
   * Check if text contains Vietnamese characters
   */
  def containsVietnameseChars(text: String): Boolean = {
    if (text == null) return false
    
    // Vietnamese-specific characters
    val vietnamesePattern = "[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]".r
    vietnamesePattern.findFirstIn(text.toLowerCase).isDefined
  }
  
  /**
   * Spark UDF for language detection
   */
  val detectLanguageUDF: UserDefinedFunction = udf((text: String) => {
    detectLanguage(text)
  })
  
  /**
   * Spark UDF for Vietnamese check
   */
  val isVietnameseUDF: UserDefinedFunction = udf((text: String) => {
    isVietnamese(text)
  })
}
