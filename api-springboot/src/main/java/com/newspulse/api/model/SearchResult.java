package com.newspulse.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Search result wrapper with pagination
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult<T> {

    private List<T> items;
    private long total;
    private int page;
    private int limit;
    private int totalPages;
    private String query;
    private long took; // Time in milliseconds
}
