package com.newspulse.crawler.model

import play.api.libs.json._
import java.time.Instant

/**
 * Represents a news article
 */
case class Article(
  id: String,
  url: String,
  title: String,
  description: Option[String],
  content: String,
  author: Option[String],
  source: String,
  category: Option[String],
  tags: List[String],
  imageUrl: Option[String],
  publishTime: Option[Instant],
  crawlTime: Instant
)

object Article:
  given Format[Instant] = new Format[Instant]:
    def reads(json: JsValue): JsResult[Instant] = 
      json.validate[Long].map(Instant.ofEpochMilli)
    def writes(instant: Instant): JsValue = 
      JsNumber(instant.toEpochMilli)

  given Format[Article] = Json.format[Article]
  
  def generateId(url: String): String =
    val digest = java.security.MessageDigest.getInstance("MD5")
    val hash = digest.digest(url.getBytes("UTF-8"))
    hash.map("%02x".format(_)).mkString
