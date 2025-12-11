package com.newspulse.crawler.sources

import com.newspulse.crawler.model.Article
import org.jsoup.nodes.{Document, Element}

import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Crawler for VNExpress (https://vnexpress.net)
 */
class VNExpressCrawler(
  override val baseUrl: String = "https://vnexpress.net",
  override val categories: List[String] = List("/")
) extends BaseCrawler:
  
  override val sourceName: String = "vnexpress"
  
  override def getArticleUrls(categoryUrl: String): List[String] =
    fetchDocument(categoryUrl) match
      case scala.util.Success(doc) =>
        doc.select("article.item-news a.thumb-art, h3.title-news a")
          .asScala
          .map(_.attr("href"))
          .filter(_.contains("vnexpress.net"))
          .filter(_.matches(".*-\\d+\\.html$"))
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
          val title = doc.select("h1.title-detail").text()
          val description = Option(doc.select("p.description").text()).filter(_.nonEmpty)
          
          // Get main content
          val contentElements = doc.select("article.fck_detail p.Normal")
          val content = contentElements.asScala.map(_.text()).mkString(" ")
          
          // Get author
          val author = Option(doc.select("p.author_mail strong, span.author").first())
            .map(_.text())
            .filter(_.nonEmpty)
          
          // Get category
          val category = Option(doc.select("ul.breadcrumb li a").first())
            .map(_.text())
          
          // Get tags
          val tags = doc.select("meta[name=keywords]")
            .attr("content")
            .split(",")
            .map(_.trim)
            .filter(_.nonEmpty)
            .toList
          
          // Get image
          val imageUrl = Option(doc.select("meta[property=og:image]").attr("content"))
            .filter(_.nonEmpty)
          
          // Get publish time
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
            logger.warn(s"Empty content for article: $url")
            None
        }.toOption.flatten
        
      case scala.util.Failure(e) =>
        logger.error(s"Failed to parse $url: ${e.getMessage}")
        None
  
  private def parsePublishTime(doc: Document): Option[Instant] =
    Try {
      val timeStr = doc.select("span.date").text()
      // Format: "Thứ ba, 10/12/2024, 15:30 (GMT+7)"
      val pattern = """(\d{1,2})/(\d{1,2})/(\d{4}),\s*(\d{1,2}):(\d{2})""".r
      
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
