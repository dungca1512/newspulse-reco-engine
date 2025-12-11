package com.newspulse.indexer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.newspulse.indexer.model.Article;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * Main indexer application that reads from Kafka and indexes to Elasticsearch
 */
public class NewsIndexer {

    private static final Logger logger = LoggerFactory.getLogger(NewsIndexer.class);

    private final String kafkaBootstrapServers;
    private final String kafkaTopic;
    private final String consumerGroup;
    private final ElasticsearchClientWrapper esClient;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    private volatile boolean running = true;

    public NewsIndexer(
            String kafkaBootstrapServers,
            String kafkaTopic,
            String consumerGroup,
            String esHost,
            int esPort,
            String esIndex,
            int batchSize) {
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.kafkaTopic = kafkaTopic;
        this.consumerGroup = consumerGroup;
        this.batchSize = batchSize;

        // Initialize Elasticsearch client
        this.esClient = new ElasticsearchClientWrapper(esHost, esPort, esIndex);

        // Configure Jackson
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Create Kafka consumer
     */
    private KafkaConsumer<String, String> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "5000");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(batchSize));

        return new KafkaConsumer<>(props);
    }

    /**
     * Run the indexer
     */
    public void run() {
        try {
            // Create index if not exists
            esClient.createIndex();

            try (KafkaConsumer<String, String> consumer = createConsumer()) {
                consumer.subscribe(Collections.singletonList(kafkaTopic));

                logger.info("Starting indexer. Listening to topic: {}", kafkaTopic);

                List<Article> batch = new ArrayList<>();

                while (running) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));

                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            Article article = objectMapper.readValue(record.value(), Article.class);
                            batch.add(article);

                            if (batch.size() >= batchSize) {
                                indexBatch(batch);
                                batch.clear();
                            }
                        } catch (Exception e) {
                            logger.error("Error parsing article: {}", e.getMessage());
                        }
                    }

                    // Index remaining batch if any
                    if (!batch.isEmpty() && records.isEmpty()) {
                        indexBatch(batch);
                        batch.clear();
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Indexer error: {}", e.getMessage(), e);
        } finally {
            try {
                esClient.close();
            } catch (IOException e) {
                logger.error("Error closing ES client: {}", e.getMessage());
            }
        }
    }

    /**
     * Index a batch of articles
     */
    private void indexBatch(List<Article> articles) {
        try {
            esClient.bulkIndexArticles(articles);
            logger.info("Indexed batch of {} articles", articles.size());
        } catch (IOException e) {
            logger.error("Error indexing batch: {}", e.getMessage());
        }
    }

    /**
     * Stop the indexer
     */
    public void stop() {
        running = false;
    }

    /**
     * Main entry point
     */
    public static void main(String[] args) {
        // Configuration from environment variables
        String kafkaServers = getEnv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String kafkaTopic = getEnv("KAFKA_TOPIC", "news_embedding");
        String consumerGroup = getEnv("KAFKA_CONSUMER_GROUP", "search-indexer");
        String esHost = getEnv("ELASTICSEARCH_HOST", "localhost");
        int esPort = Integer.parseInt(getEnv("ELASTICSEARCH_PORT", "9200"));
        String esIndex = getEnv("ELASTICSEARCH_INDEX", "news_articles");
        int batchSize = Integer.parseInt(getEnv("BATCH_SIZE", "100"));

        logger.info("Starting NewsIndexer with config:");
        logger.info("  Kafka: {}:{}", kafkaServers, kafkaTopic);
        logger.info("  Elasticsearch: {}:{}/{}", esHost, esPort, esIndex);
        logger.info("  Batch size: {}", batchSize);

        NewsIndexer indexer = new NewsIndexer(
                kafkaServers,
                kafkaTopic,
                consumerGroup,
                esHost,
                esPort,
                esIndex,
                batchSize);

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received");
            indexer.stop();
        }));

        indexer.run();
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
