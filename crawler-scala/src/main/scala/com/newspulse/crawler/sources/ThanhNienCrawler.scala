package com.newspulse.crawler.sources

import com.newspulse.crawler.model.Article
import org.jsoup.nodes.Document

import java.time.{Instant, LocalDateTime, ZoneId}
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Crawler for Thanh Nien (https://thanhnien.vn)
 */
class ThanhNienCrawler(
  override val baseUrl: String = "https://thanhnien.vn",
  override val categories: List[String] = List("/")
) extends BaseCrawler:
  
  override val sourceName: String = "thanhnien"
  
  override def getArticleUrls(categoryUrl: String): List[String] =
    fetchDocument(categoryUrl) match
      case scala.util.Success(doc) =>
        doc.select("h2.story__title a, h3.story__title a, a.story__thumb")
          .asScala
          .map(_.attr("href"))
          .map(absoluteUrl)
          .filter(_.contains(".html"))
          .toList
          .distinct
          .take(50)
      case scala.util.Failure(e) =>
        logger.error(s"Failed to fetch $categoryUrl: ${e.getMessage}")
        List.empty
  
  override def parseArticle(url: String): Option[Article] =
    fetchDocument(url) match
      case scala.util.Success(doc) =>
        Try {
          val title = doc.select("h1.detail__title").text()
          val description = Option(doc.select("h2.detail__summary").text()).filter(_.nonEmpty)
          
          val contentElements = doc.select("div.detail__content p")
          val content = contentElements.asScala
            .map(_.text())
            .filter(_.nonEmpty)
            .mkString(" ")
          
          val author = Option(doc.select("a.detail__author, div.detail__author-name").first())
            .map(_.text())
            .filter(_.nonEmpty)
          
          val category = Option(doc.select("ul.breadcrumb li a").asScala.drop(1).headOption)
            .flatten
            .map(_.text())
          
          val tags = doc.select("div.detail__tags a")
            .asScala
            .map(_.text().trim)
            .filter(_.nonEmpty)
            .toList
          
          val imageUrl = Option(doc.select("meta[property=og:image]").attr("content"))
            .filter(_.nonEmpty)
          
          val publishTime = parsePublishTime(doc)
          
          if title.nonEmpty && content.nonEmpty then
            Some(Article(
              id = Article.generateId(url),
              url = url,
              title = cleanText(title),
              description = description.map(cleanText),
              content = cleanText(content),
              author = author,
              source = sourceName,
              category = category,
              tags = tags,
              imageUrl = imageUrl,
              publishTime = publishTime,
              crawlTime = Instant.now()
            ))
          else
            None
        }.toOption.flatten
        
      case scala.util.Failure(e) =>
        logger.error(s"Failed to parse $url: ${e.getMessage}")
        None
  
  private def parsePublishTime(doc: Document): Option[Instant] =
    Try {
      val timeStr = doc.select("div.detail__meta time").attr("datetime")
      if timeStr.nonEmpty then
        Some(Instant.parse(timeStr))
      else
        None
    }.toOption.flatten
