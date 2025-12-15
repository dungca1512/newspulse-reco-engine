package com.newspulse.api.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newspulse.api.model.Article;
import com.newspulse.api.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSimulator {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SearchService searchService;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    private static final String USER_INTERACTIONS_TOPIC = "user-interactions";
    private static final List<String> USER_IDS = IntStream.range(0, 100)
            .mapToObj(i -> "user-" + UUID.randomUUID().toString().substring(0, 8))
            .toList();
    private static final List<String> EVENT_TYPES = List.of("VIEW", "CLICK", "LIKE");

    /**
     * Runs every 30 seconds to simulate user interactions.
     * The initial delay ensures that Elasticsearch has some data first.
     */
    @Scheduled(initialDelay = 30, fixedRate = 30, timeUnit = TimeUnit.SECONDS)
    public void simulateUserInteractions() {
        log.info("🚀 Starting user interaction simulation...");

        try {
            // 1. Fetch a batch of articles from Elasticsearch
            List<Article> articles = searchService.keywordSearch("", 0, 50, null, null).getItems();

            if (articles.isEmpty()) {
                log.warn("No articles found in Elasticsearch. Skipping simulation cycle.");
                return;
            }

            int eventsToSend = 20 + random.nextInt(31); // Simulate 20 to 50 events per run
            log.info("Simulating {} events.", eventsToSend);

            for (int i = 0; i < eventsToSend; i++) {
                // 2. Pick a random user and a random article
                String userId = USER_IDS.get(random.nextInt(USER_IDS.size()));
                Article article = articles.get(random.nextInt(articles.size()));
                String eventType = EVENT_TYPES.get(random.nextInt(EVENT_TYPES.size()));

                // 3. Create the event payload
                Map<String, Object> event = Map.of(
                        "userId", userId,
                        "articleId", article.getId(),
                        "eventType", eventType,
                        "timestamp", Instant.now().toString()
                );

                // 4. Serialize to JSON and send to Kafka
                String eventJson = objectMapper.writeValueAsString(event);
                kafkaTemplate.send(USER_INTERACTIONS_TOPIC, userId, eventJson);
            }

            log.info("✅ Finished sending {} events to Kafka topic '{}'.", eventsToSend, USER_INTERACTIONS_TOPIC);

        } catch (IOException e) {
            log.error("Error fetching articles for simulation: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("An unexpected error occurred during simulation: {}", e.getMessage(), e);
        }
    }
}
