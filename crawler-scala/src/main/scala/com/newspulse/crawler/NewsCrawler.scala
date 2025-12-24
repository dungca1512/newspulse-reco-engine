package com.newspulse.crawler

import com.newspulse.crawler.kafka.NewsProducer
import com.newspulse.crawler.sources._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Success, Failure}

/**
 * Main entry point for the news crawler
 */
object NewsCrawler extends LazyLogging:
  
  val config = ConfigFactory.load()
  val producer = new NewsProducer(config)
  
  // Initialize all crawlers
  val crawlers: List[BaseCrawler] = List(
    new VNExpressCrawler(
      categories = List("/", "/thoi-su", "/the-gioi", "/kinh-doanh", "/giai-tri", "/the-thao")
    ),
    new TuoiTreCrawler(
      categories = List("/", "/thoi-su.htm", "/the-gioi.htm", "/kinh-doanh.htm")
    ),
    new ThanhNienCrawler(
      categories = List("/", "/thoi-su.html", "/the-gioi.html")
    ),
    new ZingNewsCrawler(
      categories = List("/", "/xa-hoi.html", "/the-gioi.html", "/kinh-doanh-tai-chinh.html")
    ),
    new VietnamnetCrawler(
      categories = List("/", "/thoi-su", "/kinh-doanh", "/the-gioi")
    ),
    new CafeFCrawler(
      // [FIXED] Updated category path for CafeF
      categories = List("/", "/tai-chinh-ngan-hang.chn", "/thi-truong-chung-khoan.chn")
    ),
    new Kenh14Crawler(
      categories = List("/", "/star.chn", "/doi-song.chn")
    )
  )
  
  /**
   * Run all crawlers and send articles to Kafka
   */
  def runCrawlers(): Unit =
    logger.info(s"Running ${crawlers.size} crawlers...")
    
    val startTime = System.currentTimeMillis()
    var totalArticles = 0
    var successCount = 0
    var errorCount = 0
    
    for crawler <- crawlers do
      try
        logger.info(s"Crawling ${crawler.sourceName}...")
        val articles = crawler.crawl()
        
        if articles.nonEmpty then
          val futures = producer.sendBatch(articles)
          Await.result(futures, 5.minutes)
          totalArticles += articles.size
          successCount += articles.size
          logger.info(s"Sent ${articles.size} articles from ${crawler.sourceName}")
        else
          logger.warn(s"No articles found from ${crawler.sourceName}")
          
        // [FIXED] Increased delay to avoid rate limiting (429 errors)
        Thread.sleep(3000)
      catch
        case e: Exception =>
          errorCount += 1
          logger.error(s"Error crawling ${crawler.sourceName}: ${e.getMessage}", e)
    
    producer.flush()
    
    val duration = (System.currentTimeMillis() - startTime) / 1000
    logger.info(s"Crawl complete: $totalArticles articles in ${duration}s (success: $successCount, errors: $errorCount)")
  
  /**
   * Run a single crawl cycle
   */
  def runOnce(): Unit =
    try
      runCrawlers()
    finally
      producer.close()
  
  /**
   * Run crawlers in a loop with delay
   */
  def runLoop(intervalMinutes: Int = 15): Unit =
    logger.info(s"Starting crawler loop with $intervalMinutes minute interval")
    
    while true do
      try
        runCrawlers()
        logger.info(s"Sleeping for $intervalMinutes minutes...")
        Thread.sleep(intervalMinutes * 60 * 1000)
      catch
        case e: InterruptedException =>
          logger.info("Crawler loop interrupted")
          producer.close()
          return
        case e: Exception =>
          logger.error(s"Error in crawler loop: ${e.getMessage}", e)
          Thread.sleep(60000) // Wait 1 minute on error

  def main(args: Array[String]): Unit =
    logger.info("Starting NewsPulse Crawler...")
    // Parse command line arguments
    args.toList match
      case "--loop" :: Nil =>
        runLoop()
      case "--loop" :: interval :: Nil =>
        runLoop(interval.toInt)
      case "--once" :: Nil =>
        runOnce()
      case _ =>
        // Default: run once
        runOnce()
