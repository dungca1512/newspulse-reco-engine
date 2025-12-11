package com.newspulse.crawler.kafka

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord, Callback, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import com.typesafe.scalalogging.LazyLogging
import com.typesafe.config.Config
import play.api.libs.json.Json
import com.newspulse.crawler.model.Article

import java.util.Properties
import scala.util.{Try, Success, Failure}
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Kafka producer for sending articles to the message queue
 */
class NewsProducer(config: Config) extends LazyLogging:
  
  private val kafkaConfig = config.getConfig("crawler.kafka")
  private val topic = kafkaConfig.getString("topic")
  
  private val producer: KafkaProducer[String, String] = createProducer()
  
  private def createProducer(): KafkaProducer[String, String] =
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.getString("bootstrap-servers"))
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.ACKS_CONFIG, kafkaConfig.getString("producer.acks"))
    props.put(ProducerConfig.RETRIES_CONFIG, kafkaConfig.getInt("producer.retries").toString)
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, kafkaConfig.getInt("producer.batch-size").toString)
    props.put(ProducerConfig.LINGER_MS_CONFIG, kafkaConfig.getInt("producer.linger-ms").toString)
    props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, kafkaConfig.getLong("producer.buffer-memory").toString)
    
    new KafkaProducer[String, String](props)
  
  /**
   * Send an article to Kafka
   */
  def send(article: Article): Future[RecordMetadata] =
    val promise = Promise[RecordMetadata]()
    val json = Json.toJson(article).toString()
    val record = new ProducerRecord[String, String](topic, article.id, json)
    
    producer.send(record, new Callback:
      override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit =
        if exception != null then
          logger.error(s"Failed to send article ${article.id}: ${exception.getMessage}")
          promise.failure(exception)
        else
          logger.debug(s"Sent article ${article.id} to partition ${metadata.partition()}, offset ${metadata.offset()}")
          promise.success(metadata)
    )
    
    promise.future
  
  /**
   * Send multiple articles
   */
  def sendBatch(articles: Seq[Article]): Future[Seq[RecordMetadata]] =
    Future.sequence(articles.map(send))
  
  /**
   * Flush pending records
   */
  def flush(): Unit =
    producer.flush()
  
  /**
   * Close the producer
   */
  def close(): Unit =
    logger.info("Closing Kafka producer...")
    producer.flush()
    producer.close()
    logger.info("Kafka producer closed")
