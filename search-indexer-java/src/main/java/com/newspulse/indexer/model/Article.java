package com.newspulse.indexer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Article model for Elasticsearch indexing
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Article {

    private String id;
    private String url;
    private String title;
    private String description;
    private String content;
    private String author;
    private String source;
    private String category;
    private List<String> tags;

    @JsonProperty("imageUrl")
    private String imageUrl;

    @JsonProperty("publishTime")
    private Long publishTime;

    @JsonProperty("crawlTime")
    private Long crawlTime;

    private String language;

    @JsonProperty("word_count")
    private Integer wordCount;

    @JsonProperty("cluster_id")
    private Integer clusterId;

    private List<Double> embedding;

    @JsonProperty("embedding_model")
    private String embeddingModel;

    @JsonProperty("trending_score")
    private Double trendingScore;

    /**
     * Get publish time as Instant
     */
    public Instant getPublishTimeInstant() {
        return publishTime != null ? Instant.ofEpochMilli(publishTime) : null;
    }

    /**
     * Get crawl time as Instant
     */
    public Instant getCrawlTimeInstant() {
        return crawlTime != null ? Instant.ofEpochMilli(crawlTime) : null;
    }

    /**
     * Get embedding as float array (for Elasticsearch)
     */
    public float[] getEmbeddingAsFloatArray() {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        float[] result = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            result[i] = embedding.get(i).floatValue();
        }
        return result;
    }
}
