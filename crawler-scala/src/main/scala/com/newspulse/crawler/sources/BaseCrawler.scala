package com.newspulse.crawler.sources

import com.newspulse.crawler.model.Article
import com.typesafe.scalalogging.LazyLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import java.time.Instant
import scala.util.{Try, Success, Failure}
import scala.jdk.CollectionConverters._

/**
 * Base trait for all news crawlers
 */
trait BaseCrawler extends LazyLogging:
  
  def sourceName: String
  def baseUrl: String
  def categories: List[String]
  
  protected val userAgent = "NewsPulse/1.0 (News Intelligence System)"
  protected val timeout = 30000 // 30 seconds
  
  /**
   * Fetch document from URL
   */
  protected def fetchDocument(url: String): Try[Document] =
    Try {
      logger.debug(s"Fetching: $url")
      Jsoup.connect(url)
        .userAgent(userAgent)
        .timeout(timeout)
        .get()
    }
  
  /**
   * Get list of article URLs from a category page
   */
  def getArticleUrls(categoryUrl: String): List[String]
  
  /**
   * Parse a single article page
   */
  def parseArticle(url: String): Option[Article]
  
  /**
   * Crawl all categories and return articles
   */
  def crawl(): List[Article] =
    logger.info(s"Starting crawl for $sourceName")
    
    val articles = for
      category <- categories
      url = s"$baseUrl$category"
      articleUrls <- Try(getArticleUrls(url)).toOption.toList
      articleUrl <- articleUrls
      article <- parseArticle(articleUrl)
    yield article
    
    logger.info(s"Crawled ${articles.length} articles from $sourceName")
    articles.distinctBy(_.url)
  
  /**
   * Clean text content
   */
  protected def cleanText(text: String): String =
    text
      .replaceAll("\\s+", " ")
      .replaceAll("[\\r\\n]+", " ")
      .trim
      
  /**
   * Make absolute URL
   */
  protected def absoluteUrl(url: String): String =
    if url.startsWith("http") then url
    else if url.startsWith("//") then s"https:$url"
    else if url.startsWith("/") then s"$baseUrl$url"
    else s"$baseUrl/$url"
