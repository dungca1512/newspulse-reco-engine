package com.newspulse.crawler.sources

import com.newspulse.crawler.model.Article
import org.jsoup.nodes.Document

import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Crawler for Kenh14 (https://kenh14.vn) - Entertainment news
 */
class Kenh14Crawler(
  override val baseUrl: String = "https://kenh14.vn",
  override val categories: List[String] = List("/")
) extends BaseCrawler:
  
  override val sourceName: String = "kenh14"
  
  override def getArticleUrls(categoryUrl: String): List[String] =
    fetchDocument(categoryUrl) match
      case scala.util.Success(doc) =>
        doc.select("h3.knswli-title a, h2.klwh-title a, a.knswli-thumb, h3.kds-title a")
          .asScala
          .map(_.attr("href"))
          .map(absoluteUrl)
          .filter(_.contains(".chn"))
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
          val title = doc.select("h1.kbwc-title, h1.kcn-title").text()
          val description = Option(doc.select("h2.knc-sapo, h2.kcn-sapo, meta[name=description]").first())
            .map(el => if (el.tagName() == "meta") el.attr("content") else el.text())
            .filter(_.nonEmpty)
          
          val contentElements = doc.select("div.knc-content p, div.kcn-content p")
          val content = contentElements.asScala
            .map(_.text())
            .filter(_.nonEmpty)
            .mkString(" ")
          
          val author = Option(doc.select("span.kbwcm-author, p.knc-author, div.author-name").first())
            .map(_.text())
            .filter(_.nonEmpty)
          
          val category = Option(doc.select("ul.kbwcm-breadcrumb li a, ul.kcn-breadcrumb li a").asScala.drop(1).headOption)
            .flatten
            .map(_.text())
          
          val tags = doc.select("div.knc-tags a, div.kcn-tags-new a")
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
            logger.warn(s"Could not parse title or content for $url. Title found: ${title.nonEmpty}, Content empty: ${content.isEmpty}")
            None
        }.toOption.flatten
        
      case scala.util.Failure(e) =>
        logger.error(s"Failed to parse $url: ${e.getMessage}")
        None
  
  private def parsePublishTime(doc: Document): Option[Instant] =
    Try {
      val timeStr = doc.select("span.kbwcm-time, span.kcn-time").attr("title")
      // Format: 2024-07-20 10:30:00
      if (timeStr.nonEmpty) {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val dateTime = LocalDateTime.parse(timeStr, formatter)
        Some(dateTime.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant)
      } else {
        Option(doc.select("meta[property=article:published_time]").attr("content"))
          .filter(_.nonEmpty)
          .map(Instant.parse)
      }
    }.toOption.flatten
