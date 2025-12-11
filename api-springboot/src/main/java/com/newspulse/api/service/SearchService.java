package com.newspulse.api.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.*;
import com.newspulse.api.model.Article;
import com.newspulse.api.model.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Search service for news articles
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final ElasticsearchClient esClient;

    @Value("${elasticsearch.index.articles}")
    private String articlesIndex;

    @Value("${api.search.highlight-fragment-size}")
    private int highlightFragmentSize;

    /**
     * Keyword search using BM25
     */
    public SearchResult<Article> keywordSearch(String query, int page, int limit, String source, String category)
            throws IOException {
        int from = page * limit;

        // Build query
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        // Main query on title and content
        boolQuery.must(m -> m
                .multiMatch(mm -> mm
                        .query(query)
                        .fields("title^3", "description^2", "content")
                        .type(TextQueryType.BestFields)
                        .fuzziness("AUTO")));

        // Add filters
        if (source != null && !source.isEmpty()) {
            boolQuery.filter(f -> f.term(t -> t.field("source").value(source)));
        }
        if (category != null && !category.isEmpty()) {
            boolQuery.filter(f -> f.term(t -> t.field("category").value(category)));
        }

        // Execute search
        SearchResponse<Map> response = esClient.search(s -> s
                .index(articlesIndex)
                .from(from)
                .size(limit)
                .query(q -> q.bool(boolQuery.build()))
                .highlight(h -> h
                        .fields("title", hf -> hf.numberOfFragments(1))
                        .fields("content", hf -> hf
                                .fragmentSize(highlightFragmentSize)
                                .numberOfFragments(3))
                        .preTags("<em>")
                        .postTags("</em>"))
                .sort(so -> so.score(sc -> sc.order(SortOrder.Desc))),
                Map.class);

        // Map results
        List<Article> articles = mapHitsToArticles(response.hits().hits());

        long total = response.hits().total() != null ? response.hits().total().value() : 0;
        int totalPages = (int) Math.ceil((double) total / limit);

        return SearchResult.<Article>builder()
                .items(articles)
                .total(total)
                .page(page)
                .limit(limit)
                .totalPages(totalPages)
                .query(query)
                .took(response.took())
                .build();
    }

    /**
     * Semantic search using vector similarity
     */
    public SearchResult<Article> semanticSearch(float[] queryEmbedding, int page, int limit) throws IOException {
        int from = page * limit;

        // KNN search
        SearchResponse<Map> response = esClient.search(s -> s
                .index(articlesIndex)
                .from(from)
                .size(limit)
                .knn(k -> k
                        .field("embedding")
                        .queryVector(arrayToList(queryEmbedding))
                        .k(limit)
                        .numCandidates(limit * 2)),
                Map.class);

        List<Article> articles = mapHitsToArticles(response.hits().hits());

        long total = response.hits().total() != null ? response.hits().total().value() : 0;
        int totalPages = (int) Math.ceil((double) total / limit);

        return SearchResult.<Article>builder()
                .items(articles)
                .total(total)
                .page(page)
                .limit(limit)
                .totalPages(totalPages)
                .took(response.took())
                .build();
    }

    /**
     * Hybrid search using RRF (Reciprocal Rank Fusion)
     */
    public SearchResult<Article> hybridSearch(String query, float[] queryEmbedding, int page, int limit)
            throws IOException {
        // Get keyword results
        SearchResult<Article> keywordResults = keywordSearch(query, 0, limit * 2, null, null);

        // Get semantic results
        SearchResult<Article> semanticResults = semanticSearch(queryEmbedding, 0, limit * 2);

        // Compute RRF scores
        Map<String, Double> rrfScores = new HashMap<>();
        int k = 60; // RRF constant

        // Add keyword ranks
        for (int i = 0; i < keywordResults.getItems().size(); i++) {
            String id = keywordResults.getItems().get(i).getId();
            rrfScores.merge(id, 1.0 / (k + i + 1), Double::sum);
        }

        // Add semantic ranks
        for (int i = 0; i < semanticResults.getItems().size(); i++) {
            String id = semanticResults.getItems().get(i).getId();
            rrfScores.merge(id, 1.0 / (k + i + 1), Double::sum);
        }

        // Sort by RRF score and take top limit
        Map<String, Article> articleMap = new HashMap<>();
        keywordResults.getItems().forEach(a -> articleMap.put(a.getId(), a));
        semanticResults.getItems().forEach(a -> articleMap.put(a.getId(), a));

        List<Article> fusedResults = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> {
                    Article article = articleMap.get(e.getKey());
                    article.setScore(e.getValue());
                    return article;
                })
                .toList();

        return SearchResult.<Article>builder()
                .items(fusedResults)
                .total(fusedResults.size())
                .page(page)
                .limit(limit)
                .totalPages(1)
                .query(query)
                .build();
    }

    /**
     * Get article by ID
     */
    public Optional<Article> getArticleById(String id) throws IOException {
        GetResponse<Map> response = esClient.get(g -> g
                .index(articlesIndex)
                .id(id),
                Map.class);

        if (!response.found()) {
            return Optional.empty();
        }

        return Optional.of(mapToArticle(response.source()));
    }

    /**
     * Get articles by cluster ID
     */
    @Cacheable(value = "clusterArticles", key = "#clusterId + '_' + #limit")
    public List<Article> getArticlesByCluster(int clusterId, int limit) throws IOException {
        SearchResponse<Map> response = esClient.search(s -> s
                .index(articlesIndex)
                .size(limit)
                .query(q -> q.term(t -> t.field("clusterId").value(clusterId)))
                .sort(so -> so.field(f -> f.field("publishTime").order(SortOrder.Desc))),
                Map.class);

        return mapHitsToArticles(response.hits().hits());
    }

    // Helper methods

    private List<Article> mapHitsToArticles(List<Hit<Map>> hits) {
        return hits.stream()
                .map(hit -> {
                    Article article = mapToArticle(hit.source());
                    article.setScore(hit.score());

                    // Add highlights
                    if (hit.highlight() != null) {
                        if (hit.highlight().containsKey("title")) {
                            article.setHighlightTitle(String.join("...", hit.highlight().get("title")));
                        }
                        if (hit.highlight().containsKey("content")) {
                            article.setHighlightContent(String.join("...", hit.highlight().get("content")));
                        }
                    }

                    return article;
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Article mapToArticle(Map<String, Object> source) {
        if (source == null)
            return null;

        return Article.builder()
                .id((String) source.get("id"))
                .url((String) source.get("url"))
                .title((String) source.get("title"))
                .description((String) source.get("description"))
                .content((String) source.get("content"))
                .author((String) source.get("author"))
                .source((String) source.get("source"))
                .category((String) source.get("category"))
                .tags((List<String>) source.get("tags"))
                .imageUrl((String) source.get("imageUrl"))
                .publishTime(parseInstant(source.get("publishTime")))
                .crawlTime(parseInstant(source.get("crawlTime")))
                .language((String) source.get("language"))
                .wordCount(source.get("wordCount") != null ? ((Number) source.get("wordCount")).intValue() : null)
                .clusterId(source.get("clusterId") != null ? ((Number) source.get("clusterId")).intValue() : null)
                .trendingScore(
                        source.get("trendingScore") != null ? ((Number) source.get("trendingScore")).doubleValue()
                                : null)
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

    private List<Float> arrayToList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
    }
}
