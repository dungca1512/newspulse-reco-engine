"""
Kafka consumer for processing articles and adding embeddings
"""
import os
import json
import logging
from typing import Optional
from datetime import datetime

from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import KafkaError

from .embedder import Embedder
from .models import Article, ArticleWithEmbedding

logger = logging.getLogger(__name__)


class EmbeddingConsumer:
    """
    Kafka consumer that reads cleaned articles, generates embeddings,
    and produces articles with embeddings to output topic
    """
    
    def __init__(
        self,
        embedder: Embedder,
        bootstrap_servers: str = "localhost:9092",
        input_topic: str = "news_cleaned",
        output_topic: str = "news_embedding",
        consumer_group: str = "embedding-consumer",
    ):
        self.embedder = embedder
        self.bootstrap_servers = bootstrap_servers
        self.input_topic = input_topic
        self.output_topic = output_topic
        self.consumer_group = consumer_group
        
        self.consumer: Optional[KafkaConsumer] = None
        self.producer: Optional[KafkaProducer] = None
        
    def connect(self):
        """Connect to Kafka"""
        logger.info(f"Connecting to Kafka at {self.bootstrap_servers}")
        
        self.consumer = KafkaConsumer(
            self.input_topic,
            bootstrap_servers=self.bootstrap_servers,
            group_id=self.consumer_group,
            auto_offset_reset="latest",
            enable_auto_commit=True,
            value_deserializer=lambda m: json.loads(m.decode("utf-8")),
        )
        
        self.producer = KafkaProducer(
            bootstrap_servers=self.bootstrap_servers,
            value_serializer=lambda m: json.dumps(m).encode("utf-8"),
        )
        
        logger.info("Connected to Kafka")
        
    def disconnect(self):
        """Disconnect from Kafka"""
        if self.consumer:
            self.consumer.close()
        if self.producer:
            self.producer.close()
        logger.info("Disconnected from Kafka")
        
    def process_article(self, article_data: dict) -> dict:
        """
        Process a single article and add embedding
        
        Args:
            article_data: Article dictionary from Kafka
            
        Returns:
            Article with embedding
        """
        # Parse article
        article = Article(**article_data)
        
        # Create text for embedding (title + description + content)
        text_parts = [article.title]
        if article.description:
            text_parts.append(article.description)
        text_parts.append(article.content[:2000])  # Limit content length
        
        embed_text = " ".join(text_parts)
        
        # Generate embedding
        embedding = self.embedder.embed(embed_text)
        
        # Create article with embedding
        result = article_data.copy()
        result["embedding"] = embedding
        result["embedding_model"] = self.embedder.model_name
        result["embedding_time"] = datetime.utcnow().isoformat()
        
        return result
        
    def run(self, batch_size: int = 10):
        """
        Run the consumer loop
        
        Args:
            batch_size: Number of articles to batch for embedding
        """
        if not self.consumer or not self.producer:
            self.connect()
            
        logger.info(f"Starting embedding consumer on topic: {self.input_topic}")
        
        batch = []
        
        try:
            for message in self.consumer:
                try:
                    article_data = message.value
                    
                    # Process article
                    result = self.process_article(article_data)
                    
                    # Send to output topic
                    self.producer.send(self.output_topic, value=result)
                    
                    logger.debug(f"Processed article: {result.get('id')}")
                    
                except Exception as e:
                    logger.error(f"Error processing message: {e}")
                    continue
                    
        except KeyboardInterrupt:
            logger.info("Consumer interrupted")
        finally:
            self.disconnect()


def main():
    """Main entry point for the consumer"""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    )
    
    # Initialize embedder
    model_name = os.getenv("MODEL_NAME", "paraphrase-multilingual-mpnet-base-v2")
    embedder = Embedder(model_name)
    
    # Initialize and run consumer
    consumer = EmbeddingConsumer(
        embedder=embedder,
        bootstrap_servers=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
        input_topic=os.getenv("KAFKA_INPUT_TOPIC", "news_cleaned"),
        output_topic=os.getenv("KAFKA_OUTPUT_TOPIC", "news_embedding"),
        consumer_group=os.getenv("KAFKA_CONSUMER_GROUP", "embedding-consumer"),
    )
    
    consumer.run()


if __name__ == "__main__":
    main()
