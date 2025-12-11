package com.newspulse.api.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.newspulse.api.model.Article;
import com.newspulse.api.model.TrendingTopic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Service for trending topics
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrendingService {

    private final ElasticsearchClient esClient;
    private final SearchService searchService;

    @Value("${elasticsearch.index.trending}")
    private String trendingIndex;

    @Value("${elasticsearch.index.articles}")
    private String articlesIndex;

    /**
     * Get trending topics
     */
    @Cacheable(value = "trending", key = "#limit + '_' + #category")
    public List<TrendingTopic> getTrending(int limit, String category) throws IOException {
        var queryBuilder = co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.builder();

        if (category != null && !category.isEmpty()) {
            queryBuilder.filter(f -> f.term(t -> t.field("category").value(category)));
        }

        SearchResponse<Map> response = esClient.search(s -> s
                .index(trendingIndex)
                .size(limit)
                .query(q -> q.bool(queryBuilder.build()))
                .sort(so -> so.field(f -> f.field("trending_score").order(SortOrder.Desc))),
                Map.class);

        return response.hits().hits().stream()
                .map(hit -> mapToTrendingTopic(hit.source()))
                .toList();
    }

    /**
     * Get breaking news (recent spikes)
     */
    @Cacheable(value = "breaking", key = "#limit")
    public List<TrendingTopic> getBreakingNews(int limit) throws IOException {
        // Get topics with is_breaking = true or very recent high velocity
        SearchResponse<Map> response = esClient.search(s -> s
                .index(trendingIndex)
                .size(limit)
                .query(q -> q
                        .bool(b -> b
                                .should(sh -> sh.term(t -> t.field("is_spike").value(true)))
                                .should(sh -> sh.range(r -> r
                                        .field("velocity")
                                        .gte(co.elastic.clients.json.JsonData.of(5.0))))
                                .minimumShouldMatch("1")))
                .sort(so -> so.field(f -> f.field("trending_score").order(SortOrder.Desc))),
                Map.class);

        List<TrendingTopic> topics = response.hits().hits().stream()
                .map(hit -> {
                    TrendingTopic topic = mapToTrendingTopic(hit.source());
                    topic.setIsBreaking(true);
                    return topic;
                })
                .toList();

        return topics;
    }

    /**
     * Get topic details with sample articles
     */
    public Optional<TrendingTopic> getTopicDetails(int clusterId) throws IOException {
        // Search for the topic
        SearchResponse<Map> response = esClient.search(s -> s
                .index(trendingIndex)
                .size(1)
                .query(q -> q.term(t -> t.field("cluster_id").value(clusterId))),
                Map.class);

        if (response.hits().hits().isEmpty()) {
            return Optional.empty();
        }

        TrendingTopic topic = mapToTrendingTopic(response.hits().hits().get(0).source());

        // Get sample articles
        List<Article> sampleArticles = searchService.getArticlesByCluster(clusterId, 10);
        topic.setSampleArticles(sampleArticles);

        return Optional.of(topic);
    }

    /**
     * Get trending by category
     */
    public Map<String, List<TrendingTopic>> getTrendingByCategory(int topPerCategory) throws IOException {
        // Get all trending
        List<TrendingTopic> allTrending = getTrending(100, null);

        // Group by category
        Map<String, List<TrendingTopic>> byCategory = new HashMap<>();
        for (TrendingTopic topic : allTrending) {
            String cat = topic.getCategory() != null ? topic.getCategory() : "other";
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>());

            if (byCategory.get(cat).size() < topPerCategory) {
                byCategory.get(cat).add(topic);
            }
        }

        return byCategory;
    }

    @SuppressWarnings("unchecked")
    private TrendingTopic mapToTrendingTopic(Map<String, Object> source) {
        if (source == null)
            return null;

        return TrendingTopic.builder()
                .clusterId(source.get("cluster_id") != null ? ((Number) source.get("cluster_id")).intValue() : null)
                .title((String) source.get("title"))
                .label((String) source.get("generated_label"))
                .articleCount(
                        source.get("article_count") != null ? ((Number) source.get("article_count")).intValue() : null)
                .sourceCount(
                        source.get("source_count") != null ? ((Number) source.get("source_count")).intValue() : null)
                .sources((List<String>) source.get("sources"))
                .trendingScore(
                        source.get("trending_score") != null ? ((Number) source.get("trending_score")).doubleValue()
                                : null)
                .rank(source.get("rank") != null ? ((Number) source.get("rank")).intValue() : null)
                .category((String) source.get("category"))
                .imageUrl((String) source.get("image_url"))
                .earliestPublish(parseInstant(source.get("earliest_publish")))
                .latestPublish(parseInstant(source.get("latest_publish")))
                .isBreaking(Boolean.TRUE.equals(source.get("is_spike")))
                .build();
    }

    private Instant parseInstant(Object value) {
        if (value == null)
            return null;
        if (value instanceof Long)
            return Instant.ofEpochMilli((Long) value);
        if (value instanceof String)
            return Instant.parse((String) value);
        return null;
    }
}
