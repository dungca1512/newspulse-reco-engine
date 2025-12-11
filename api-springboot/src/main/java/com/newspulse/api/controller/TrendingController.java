package com.newspulse.api.controller;

import com.newspulse.api.model.TrendingTopic;
import com.newspulse.api.service.TrendingService;
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
import java.util.Map;

/**
 * Trending API controller
 */
@RestController
@RequestMapping("/api/trending")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Trending", description = "Trending topics APIs")
public class TrendingController {

    private final TrendingService trendingService;

    @Value("${api.trending.default-limit}")
    private int defaultLimit;

    @Value("${api.trending.max-limit}")
    private int maxLimit;

    /**
     * Get trending topics
     */
    @GetMapping
    @Operation(summary = "Get trending", description = "Get currently trending topics")
    public ResponseEntity<List<TrendingTopic>> getTrending(
            @Parameter(description = "Number of topics to return") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Filter by category") @RequestParam(required = false) String category)
            throws IOException {
        int resultLimit = limit != null ? Math.min(limit, maxLimit) : defaultLimit;

        log.info("Getting trending topics: limit={}, category={}", resultLimit, category);

        List<TrendingTopic> trending = trendingService.getTrending(resultLimit, category);
        return ResponseEntity.ok(trending);
    }

    /**
     * Get breaking news
     */
    @GetMapping("/breaking")
    @Operation(summary = "Get breaking news", description = "Get breaking/emerging news topics")
    public ResponseEntity<List<TrendingTopic>> getBreaking(
            @Parameter(description = "Number of topics to return") @RequestParam(defaultValue = "10") int limit)
            throws IOException {
        log.info("Getting breaking news: limit={}", limit);

        List<TrendingTopic> breaking = trendingService.getBreakingNews(Math.min(limit, maxLimit));
        return ResponseEntity.ok(breaking);
    }

    /**
     * Get topic details
     */
    @GetMapping("/{clusterId}")
    @Operation(summary = "Get topic details", description = "Get details of a specific trending topic")
    public ResponseEntity<TrendingTopic> getTopicDetails(
            @Parameter(description = "Cluster/Topic ID") @PathVariable int clusterId) throws IOException {
        log.info("Getting topic details: clusterId={}", clusterId);

        return trendingService.getTopicDetails(clusterId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get trending by category
     */
    @GetMapping("/by-category")
    @Operation(summary = "Get trending by category", description = "Get trending topics grouped by category")
    public ResponseEntity<Map<String, List<TrendingTopic>>> getTrendingByCategory(
            @Parameter(description = "Topics per category") @RequestParam(defaultValue = "5") int topPerCategory)
            throws IOException {
        log.info("Getting trending by category: topPerCategory={}", topPerCategory);

        Map<String, List<TrendingTopic>> byCategory = trendingService.getTrendingByCategory(topPerCategory);
        return ResponseEntity.ok(byCategory);
    }
}
