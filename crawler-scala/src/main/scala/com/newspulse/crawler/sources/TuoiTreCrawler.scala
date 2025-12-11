package com.newspulse.crawler.sources

import com.newspulse.crawler.model.Article
import org.jsoup.nodes.Document

import java.time.{Instant, LocalDateTime, ZoneId}
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Crawler for Tuoi Tre (https://tuoitre.vn)
 */
class TuoiTreCrawler(
  override val baseUrl: String = "https://tuoitre.vn",
  override val categories: List[String] = List("/")
) extends BaseCrawler:
  
  override val sourceName: String = "tuoitre"
  
  override def getArticleUrls(categoryUrl: String): List[String] =
    fetchDocument(categoryUrl) match
      case scala.util.Success(doc) =>
        doc.select("h3.title-name a, h2.title-name a, a.box-category-link-title")
          .asScala
          .map(_.attr("href"))
          .map(absoluteUrl)
          .filter(_.contains(".htm"))
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
          val title = doc.select("h1.article-title").text()
          val description = Option(doc.select("h2.sapo").text()).filter(_.nonEmpty)
          
          val contentElements = doc.select("div#main-detail-body p, div.detail-content p")
          val content = contentElements.asScala
            .map(_.text())
            .filter(!_.contains("Xem thêm"))
            .mkString(" ")
          
          val author = Option(doc.select("div.author-info a, span.name-author").first())
            .map(_.text())
            .filter(_.nonEmpty)
          
          val category = Option(doc.select("div.bread-crumb a").get(1))
            .map(_.text())
          
          val tags = doc.select("div.tags-container a")
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
      val timeStr = doc.select("div.date-time").text()
      // Format: "10/12/2024 15:30 GMT+7"
      val pattern = """(\d{1,2})/(\d{1,2})/(\d{4})\s+(\d{1,2}):(\d{2})""".r
      
      timeStr match
        case pattern(day, month, year, hour, minute) =>
          val dateTime = LocalDateTime.of(
            year.toInt, month.toInt, day.toInt,
            hour.toInt, minute.toInt
          )
          Some(dateTime.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant)
        case _ =>
          None
    }.toOption.flatten
