package com.newspulse.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Article model for API responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Article implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String url;
    private String title;
    private String description;
    private String content;
    private String author;
    private String source;
    private String category;
    private List<String> tags;
    private String imageUrl;
    private Instant publishTime;
    private Instant crawlTime;
    private String language;
    private Integer wordCount;
    private Integer clusterId;
    private Double trendingScore;

    // For search results
    private Double score;
    private String highlightTitle;
    private String highlightContent;
}
