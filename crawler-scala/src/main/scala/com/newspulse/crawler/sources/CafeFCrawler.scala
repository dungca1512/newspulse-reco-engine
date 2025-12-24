package com.newspulse.crawler.sources

import com.newspulse.crawler.model.Article
import org.jsoup.nodes.Document

import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Crawler for CafeF (https://cafef.vn) - Financial news
 */
class CafeFCrawler(
  override val baseUrl: String = "https://cafef.vn",
  override val categories: List[String] = List("/")
) extends BaseCrawler:
  
  override val sourceName: String = "cafef"
  
  override def getArticleUrls(categoryUrl: String): List[String] =
    fetchDocument(categoryUrl) match
      case scala.util.Success(doc) =>
        doc.select("h3 a, h2 a, div.avatar a")
          .asScala
          .map(_.attr("href"))
          .map(absoluteUrl)
          .filter(_.matches(".*-\\d+\\.chn$"))
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
          val title = doc.select("h1.title, h1.detail-title").text()
          val description = Option(doc.select("h2.sapo, h2.detail-sapo").text()).filter(_.nonEmpty)
          
          val contentElements = doc.select("div.detail-content p, div#mainContent p")
          val content = contentElements.asScala
            .map(_.text())
            .filter(_.nonEmpty)
            .mkString(" ")
          
          val author = Option(doc.select("p.author, div.author, span.author").first())
            .map(_.text())
            .filter(_.nonEmpty)
          
          val category = Option(doc.select("div.breadcrumb a, ul.breadcrumb li a").asScala.drop(1).headOption)
            .flatten
            .map(_.text())
          
          val tags = doc.select("div.tags a, div.tag-cloud a")
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
      val timeStr = doc.select("span.pdate, span.date, time.op-published-time").text()
      // Format: 10/12/2024 15:30
      val pattern = """(\d{1,2})/(\d{1,2})/(\d{4})\s+(\d{1,2}):(\d{2})""".r
      
      timeStr match
        case pattern(day, month, year, hour, minute) =>
          val dateTime = LocalDateTime.of(
            year.toInt, month.toInt, day.toInt,
            hour.toInt, minute.toInt
          )
          Some(dateTime.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant)
        case _ =>
          Option(doc.select("meta[property=article:published_time]").attr("content"))
            .map(Instant.parse)
    }.toOption.flatten
