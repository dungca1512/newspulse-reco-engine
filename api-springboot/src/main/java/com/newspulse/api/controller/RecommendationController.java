package com.newspulse.api.controller;

import com.newspulse.api.model.Article;
import com.newspulse.api.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * Recommendation API controller
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Recommendations", description = "Article recommendation APIs")
public class RecommendationController {

    private final RecommendationService recommendationService;

    @Value("${api.recommendations.default-limit}")
    private int defaultLimit;

    @Value("${api.recommendations.max-limit}")
    private int maxLimit;

    /**
     * Get related articles
     */
    @GetMapping("/articles/{id}/related")
    @Operation(summary = "Get related articles", description = "Get articles related to the specified article")
    public ResponseEntity<List<Article>> getRelatedArticles(
            @Parameter(description = "Article ID") @PathVariable String id,
            @Parameter(description = "Number of related articles") @RequestParam(required = false) Integer limit)
            throws IOException {
        int resultLimit = limit != null ? Math.min(limit, maxLimit) : defaultLimit;

        log.info("Getting related articles for: id={}, limit={}", id, resultLimit);

        List<Article> related = recommendationService.getRelatedArticles(id, resultLimit);
        return ResponseEntity.ok(related);
    }

    /**
     * Get personalized recommendations
     */
    @PostMapping("/recommendations")
    @Operation(summary = "Get recommendations", description = "Get personalized recommendations based on reading history")
    public ResponseEntity<List<Article>> getRecommendations(
            @RequestBody RecommendationRequest request,
            @Parameter(description = "Number of recommendations") @RequestParam(required = false) Integer limit)
            throws IOException {
        int resultLimit = limit != null ? Math.min(limit, maxLimit) : defaultLimit;

        log.info("Getting recommendations: history size={}, limit={}",
                request.readArticleIds() != null ? request.readArticleIds().size() : 0,
                resultLimit);

        List<Article> recommendations = recommendationService.getPersonalizedRecommendations(
                request.readArticleIds() != null ? request.readArticleIds() : List.of(),
                resultLimit);

        return ResponseEntity.ok(recommendations);
    }

    // Request DTOs
    public record RecommendationRequest(List<String> readArticleIds) {
    }
}
