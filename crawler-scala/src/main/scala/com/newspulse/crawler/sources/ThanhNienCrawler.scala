package com.newspulse.crawler.sources

import com.newspulse.crawler.model.Article
import org.jsoup.nodes.Document

import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
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
        // Updated selectors for article links, more specific to avoid ads/non-news
        doc.select("article.story a[href*='.html'], div.zone--timeline article a[href*='.html']")
          .asScala
          .map(_.attr("href"))
          .map(absoluteUrl)
          .filter(_.matches(".*/post\\d+\\.html$")) // Ensure it's a post URL
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
          val title = doc.select("h1.detail__title, h1.article-title").text()
          val description = Option(doc.select("h2.detail__summary, div.sapo, meta[name=description]").first())
            .map { element => if (element.tagName() == "meta") element.attr("content") else element.text() }
            .filter(_.nonEmpty)
          
          val contentElements = doc.select("div.detail__content div.cms-body > p, div#abody p, div.article-content p")
          val content = contentElements.asScala
            .map(_.text())
            .filter(_.nonEmpty)
            .mkString(" ")
          
          val author = Option(doc.select("a.detail__author, div.detail__author-name, div.author-info__name").first())
            .map(_.text())
            .filter(_.nonEmpty)
          
          val category = Option(doc.select("ul.breadcrumb li a, div.breadcrumbs a").asScala.drop(1).headOption)
            .flatten
            .map(_.text())
          
          val tags = doc.select("div.detail__tags a, div.tags a")
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
      // ThanhNien uses a 'datetime' attribute in a <time> tag
      val timeStr = doc.select("div.detail__meta time, time.article-meta-time").attr("datetime")
      if timeStr.nonEmpty then
        Some(Instant.parse(timeStr))
      else
        // Fallback for other formats
        val timeText = doc.select("div.detail__meta, span.meta-time").text()
        // Format: 14:30 - 19/12/2025
        val pattern = """(\d{2}):(\d{2})\s-\s(\d{2})/(\d{2})/(\d{4})""".r
        timeText match {
          case pattern(hour, minute, day, month, year) =>
            val dt = LocalDateTime.of(year.toInt, month.toInt, day.toInt, hour.toInt, minute.toInt)
            Some(dt.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant)
          case _ =>
            Option(doc.select("meta[property=article:published_time]").attr("content"))
              .filter(_.nonEmpty)
              .map(Instant.parse)
        }
    }.toOption.flatten
