package com.newspulse.api.service;

import com.newspulse.api.model.Article;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Service for article recommendations using embeddings
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationService {

    private final SearchService searchService;
    private final WebClient.Builder webClientBuilder;

    @Value("${embedding.service.url}")
    private String embeddingServiceUrl;

    /**
     * Get related articles based on semantic similarity
     */
    public List<Article> getRelatedArticles(String articleId, int limit) throws IOException {
        // Get the article
        Article article = searchService.getArticleById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("Article not found: " + articleId));

        // Get embedding for the article
        float[] embedding = getEmbedding(article.getTitle() + " " + article.getDescription());

        // Find similar articles
        List<Article> similar = searchService.semanticSearch(embedding, 0, limit + 1)
                .getItems()
                .stream()
                .filter(a -> !a.getId().equals(articleId)) // Exclude the source article
                .limit(limit)
                .toList();

        return similar;
    }

    /**
     * Get personalized recommendations based on reading history
     */
    public List<Article> getPersonalizedRecommendations(List<String> readArticleIds, int limit) throws IOException {
        if (readArticleIds.isEmpty()) {
            // Return trending if no history
            return searchService.keywordSearch("", 0, limit, null, null).getItems();
        }

        // Get embeddings for read articles and average them
        StringBuilder contentBuilder = new StringBuilder();
        for (String id : readArticleIds) {
            searchService.getArticleById(id).ifPresent(article -> {
                contentBuilder.append(article.getTitle()).append(" ");
            });
        }

        float[] profileEmbedding = getEmbedding(contentBuilder.toString());

        // Find similar articles
        List<Article> recommendations = searchService.semanticSearch(profileEmbedding, 0, limit + readArticleIds.size())
                .getItems()
                .stream()
                .filter(a -> !readArticleIds.contains(a.getId())) // Exclude read articles
                .limit(limit)
                .toList();

        return recommendations;
    }

    /**
     * Get embedding from embedding service
     */
    private float[] getEmbedding(String text) {
        try {
            WebClient webClient = webClientBuilder.baseUrl(embeddingServiceUrl).build();

            EmbedResponse response = webClient.post()
                    .uri("/embed")
                    .bodyValue(Map.of("text", text))
                    .retrieve()
                    .bodyToMono(EmbedResponse.class)
                    .block();

            if (response != null && response.embedding != null) {
                float[] result = new float[response.embedding.size()];
                for (int i = 0; i < response.embedding.size(); i++) {
                    result[i] = response.embedding.get(i).floatValue();
                }
                return result;
            }
        } catch (Exception e) {
            log.error("Error getting embedding: {}", e.getMessage());
        }

        // Return zero vector on error
        return new float[768];
    }

    private static class EmbedResponse {
        public List<Double> embedding;
        public String model;
        public int dimensions;
    }
}
