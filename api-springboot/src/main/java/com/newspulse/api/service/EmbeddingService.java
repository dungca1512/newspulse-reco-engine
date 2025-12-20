package com.newspulse.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Map;

/**
 * Service for generating text embeddings via external embedding service
 */
@Service
@Slf4j
public class EmbeddingService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String embeddingServiceUrl;

    public EmbeddingService(
            @Value("${embedding.service.url}") String embeddingServiceUrl,
            ObjectMapper objectMapper) {
        this.embeddingServiceUrl = embeddingServiceUrl;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Generate embedding for a text query
     */
    public float[] getEmbedding(String text) throws IOException {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> requestBody = Map.of("text", text);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            log.debug("Embedding request: url={}, body={}", embeddingServiceUrl + "/embed", requestBody);

            ResponseEntity<String> response = restTemplate.exchange(
                    embeddingServiceUrl + "/embed",
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() != HttpStatus.OK) {
                throw new IOException("Embedding service returned status " + response.getStatusCode());
            }

            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            JsonNode embeddingNode = jsonResponse.get("embedding");

            if (embeddingNode == null || !embeddingNode.isArray()) {
                throw new IOException("Invalid embedding response format");
            }

            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }

            log.debug("Generated embedding for text of length {} with {} dimensions", text.length(), embedding.length);
            return embedding;

        } catch (Exception e) {
            log.error("Embedding service error: {}", e.getMessage());
            throw new IOException("Embedding service error: " + e.getMessage(), e);
        }
    }

    /**
     * Check if embedding service is available
     */
    public boolean isAvailable() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    embeddingServiceUrl + "/health",
                    String.class
            );
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.warn("Embedding service health check failed: {}", e.getMessage());
            return false;
        }
    }
}