package com.newspulse.crawler.sources

import com.newspulse.crawler.model.Article
import org.jsoup.nodes.Document

import java.time.{Instant, LocalDateTime, ZoneId}
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Crawler for Zing News (https://zingnews.vn)
 */
class ZingNewsCrawler(
  override val baseUrl: String = "https://zingnews.vn",
  override val categories: List[String] = List("/")
) extends BaseCrawler:
  
  override val sourceName: String = "zingnews"
  
  override def getArticleUrls(categoryUrl: String): List[String] =
    fetchDocument(categoryUrl) match
      case scala.util.Success(doc) =>
        doc.select("p.article-title a, h2.article-title a")
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
          val title = doc.select("h1.the-article-title").text()
          val description = Option(doc.select("p.the-article-summary").text()).filter(_.nonEmpty)
          
          val contentElements = doc.select("div.the-article-body p.Normal")
          val content = contentElements.asScala
            .map(_.text())
            .filter(_.nonEmpty)
            .mkString(" ")
          
          val author = Option(doc.select("p.the-article-author, span.author").first())
            .map(_.text())
            .filter(_.nonEmpty)
          
          val category = Option(doc.select("p.the-article-category a").first())
            .map(_.text())
          
          val tags = doc.select("ul.the-article-tags li a")
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
      val timeStr = doc.select("li.the-article-publish").text()
      val pattern = """(\d{1,2}):(\d{2})\s+(\d{1,2})/(\d{1,2})/(\d{4})""".r
      
      timeStr match
        case pattern(hour, minute, day, month, year) =>
          val dateTime = LocalDateTime.of(
            year.toInt, month.toInt, day.toInt,
            hour.toInt, minute.toInt
          )
          Some(dateTime.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant)
        case _ =>
          None
    }.toOption.flatten
