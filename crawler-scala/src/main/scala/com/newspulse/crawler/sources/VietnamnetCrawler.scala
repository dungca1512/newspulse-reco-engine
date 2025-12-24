package com.newspulse.crawler.sources

import com.newspulse.crawler.model.Article
import org.jsoup.nodes.Document

import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Crawler for VietnamNet (https://vietnamnet.vn)
 */
class VietnamnetCrawler(
  override val baseUrl: String = "https://vietnamnet.vn",
  override val categories: List[String] = List("/")
) extends BaseCrawler:
  
  override val sourceName: String = "vietnamnet"
  
  override def getArticleUrls(categoryUrl: String): List[String] =
    fetchDocument(categoryUrl) match
      case scala.util.Success(doc) =>
        // Updated selectors for article links
        doc.select("h2.vnn-title a, h3.vnn-title a, a.vertical-sub-news__title")
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
          val title = doc.select("h1.content-title, h1.article-title").text()
          val description = Option(doc.select("div.content-sapo h2, p.article-sapo").text()).filter(_.nonEmpty)
          
          val contentElements = doc.select("div.main-content p, div.article-body p")
          val content = contentElements.asScala
            .map(_.text())
            .filter(_.nonEmpty)
            .mkString(" ")
          
          val author = Option(doc.select("div.content-author-name a, div.author-info__name").first())
            .map(_.text())
            .filter(_.nonEmpty)
          
          val category = Option(doc.select("div.feature-box a.feature-box__title, ul.breadcrumb__links li a").asScala.drop(1).headOption)
            .flatten
            .map(_.text())
          
          val tags = doc.select("div.related-tags a, ul.tags-list__items li a")
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
            logger.warn(s"Could not parse title or content for $url. Title found: ${title.nonEmpty}")
            None
        }.toOption.flatten
        
      case scala.util.Failure(e) =>
        logger.error(s"Failed to parse $url: ${e.getMessage}")
        None
  
  private def parsePublishTime(doc: Document): Option[Instant] =
    Try {
      val timeStr = doc.select("div.content-date-time, div.article-publish-date__time").text()
      // Format: 19/07/2024 14:00 (GMT+07:00)
      val pattern = """(\d{2})/(\d{2})/(\d{4})\s(\d{2}):(\d{2})""".r
      
      timeStr match
        case pattern(day, month, year, hour, minute) =>
          val dateTime = LocalDateTime.of(
            year.toInt, month.toInt, day.toInt,
            hour.toInt, minute.toInt
          )
          Some(dateTime.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant)
        case _ =>
          Option(doc.select("meta[property=article:published_time]").attr("content"))
            .filter(_.nonEmpty)
            .map(Instant.parse)
    }.toOption.flatten
