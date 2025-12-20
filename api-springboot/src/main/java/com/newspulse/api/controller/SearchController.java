package com.newspulse.api.controller;

import com.newspulse.api.model.SearchResult;
import com.newspulse.api.model.Article;
import com.newspulse.api.service.SearchService;
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
import java.util.Map;

/**
 * Search API controller
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Search", description = "News article search APIs")
public class SearchController {

    private final SearchService searchService;
    private final RecommendationService recommendationService;
    private final com.newspulse.api.service.EmbeddingService embeddingService;

    @Value("${api.search.default-limit}")
    private int defaultLimit;

    @Value("${api.search.max-limit}")
    private int maxLimit;

    /**
     * Keyword search using BM25
     */
    @GetMapping
    @Operation(summary = "Keyword search", description = "Search articles using BM25 text matching")
    public ResponseEntity<SearchResult<Article>> keywordSearch(
            @Parameter(description = "Search query") @RequestParam String q,
            @Parameter(description = "Page number (0-indexed)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Results per page") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Filter by source") @RequestParam(required = false) String source,
            @Parameter(description = "Filter by category") @RequestParam(required = false) String category)
            throws IOException {
        int resultLimit = limit != null ? Math.min(limit, maxLimit) : defaultLimit;

        log.info("Keyword search: query='{}', page={}, limit={}", q, page, resultLimit);

        SearchResult<Article> result = searchService.keywordSearch(q, page, resultLimit, source, category);
        return ResponseEntity.ok(result);
    }

    /**
     * Semantic search using vector similarity
     */
    @PostMapping("/semantic")
    @Operation(summary = "Semantic search", description = "Search articles using vector similarity")
    public ResponseEntity<SearchResult<Article>> semanticSearch(
            @RequestBody SemanticSearchRequest request,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Results per page") @RequestParam(required = false) Integer limit)
            throws IOException {
        int resultLimit = limit != null ? Math.min(limit, maxLimit) : defaultLimit;

        log.info("Semantic search: text length={}, page={}", request.text().length(), page);

        // Get embedding from embedding service
        float[] embedding = embeddingService.getEmbedding(request.text());

        SearchResult<Article> result = searchService.semanticSearch(embedding, page, resultLimit);
        return ResponseEntity.ok(result);
    }

    /**
     * Hybrid search combining keyword and semantic
     */
    @GetMapping("/hybrid")
    @Operation(summary = "Hybrid search", description = "Search using both BM25 and vector similarity with RRF fusion")
    public ResponseEntity<SearchResult<Article>> hybridSearch(
            @Parameter(description = "Search query") @RequestParam String q,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Results per page") @RequestParam(required = false) Integer limit)
            throws IOException {
        int resultLimit = limit != null ? Math.min(limit, maxLimit) : defaultLimit;

        log.info("Hybrid search: query='{}', page={}", q, page);

        // Get embedding from embedding service
        float[] embedding = embeddingService.getEmbedding(q);

        SearchResult<Article> result = searchService.hybridSearch(q, embedding, page, resultLimit);
        return ResponseEntity.ok(result);
    }

    /**
     * Get article by ID
     */
    @GetMapping("/articles/{id}")
    @Operation(summary = "Get article", description = "Get article by ID")
    public ResponseEntity<Article> getArticle(
            @Parameter(description = "Article ID") @PathVariable String id) throws IOException {
        return searchService.getArticleById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get available sources and categories for filtering
     */
    @GetMapping("/filters")
    @Operation(summary = "Get filters", description = "Get available filter options")
    public ResponseEntity<Map<String, Object>> getFilters() {
        return ResponseEntity.ok(Map.of(
                "sources", java.util.List.of(
                        "vnexpress", "tuoitre", "thanhnien", "zingnews",
                        "vietnamnet", "cafef", "kenh14"),
                "categories", java.util.List.of(
                        "thoi-su", "the-gioi", "kinh-doanh", "giai-tri",
                        "the-thao", "phap-luat", "giao-duc", "suc-khoe",
                        "doi-song", "du-lich", "khoa-hoc", "so-hoa")));
    }

    // Request DTOs
    public record SemanticSearchRequest(String text) {
    }
}
