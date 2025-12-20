package com.newspulse.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Trending topic model
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendingTopic implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer clusterId;
    private String title;
    private String label;
    private Integer articleCount;
    private Integer sourceCount;
    private List<String> sources;
    private Double trendingScore;
    private Integer rank;
    private String category;
    private String imageUrl;
    private Instant earliestPublish;
    private Instant latestPublish;
    private Boolean isBreaking;

    // Sample articles in this topic
    private List<Article> sampleArticles;
}
