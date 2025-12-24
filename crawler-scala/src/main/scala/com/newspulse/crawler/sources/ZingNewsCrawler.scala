package com.newspulse.crawler.sources

import com.newspulse.crawler.model.Article
import org.jsoup.nodes.Document

import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Crawler for ZNews (formerly Zing News) (https://znews.vn)
 */
class ZingNewsCrawler(
  override val baseUrl: String = "https://znews.vn",
  override val categories: List[String] = List("/")
) extends BaseCrawler:
  
  override val sourceName: String = "znews" // Updated source name
  
  override def getArticleUrls(categoryUrl: String): List[String] =
    fetchDocument(categoryUrl) match
      case scala.util.Success(doc) =>
        doc.select("p.article-title a, h2.article-title a, article.article-item a")
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
          val title = doc.select("h1.the-article-title, h1.article-title").text()
          val description = Option(doc.select("p.the-article-summary, p.article-summary").text()).filter(_.nonEmpty)
          
          val contentElements = doc.select("div.the-article-body p, div.article-body p")
          val content = contentElements.asScala
            .map(_.text())
            .filter(_.nonEmpty)
            .mkString(" ")
          
          val author = Option(doc.select("p.the-article-author, span.author, li.author").first())
            .map(_.text())
            .filter(_.nonEmpty)
          
          val category = Option(doc.select("p.the-article-category a, p.article-category a").first())
            .map(_.text())
          
          val tags = doc.select("ul.the-article-tags li a, div.article-tags a")
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
      val timeStr = doc.select("li.the-article-publish, time.article-publish-time").attr("datetime")
      if (timeStr.nonEmpty) {
        Some(Instant.parse(timeStr))
      } else {
        val timeText = doc.select("li.the-article-publish, time.article-publish-time").text()
        val pattern = """(\d{2})/(\d{2})/(\d{4})\s(\d{2}):(\d{2})""".r
        timeText match {
          case pattern(day, month, year, hour, minute) =>
            val dt = LocalDateTime.of(year.toInt, month.toInt, day.toInt, hour.toInt, minute.toInt)
            Some(dt.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant)
          case _ =>
            Option(doc.select("meta[property=article:published_time]").attr("content"))
              .map(Instant.parse)
        }
      }
    }.toOption.flatten
