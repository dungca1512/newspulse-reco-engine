package com.newspulse.indexer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.*;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.newspulse.indexer.model.Article;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Elasticsearch client wrapper for news article indexing
 */
public class ElasticsearchClientWrapper implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchClientWrapper.class);

    private final RestClient restClient;
    private final ElasticsearchTransport transport;
    private final ElasticsearchClient client;
    private final String indexName;

    public ElasticsearchClientWrapper(String host, int port, String indexName) {
        this.indexName = indexName;

        // Create low-level REST client
        this.restClient = RestClient.builder(
                new HttpHost(host, port, "http")).build();

        // Create ObjectMapper with Java 8 date/time support
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Create transport with configured Jackson mapper
        this.transport = new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));

        // Create API client
        this.client = new ElasticsearchClient(transport);

        logger.info("Connected to Elasticsearch at {}:{}", host, port);
    }

    /**
     * Create the news index with proper mappings
     */
    public void createIndex() throws IOException {
        // Check if index exists
        boolean exists = client.indices().exists(e -> e.index(indexName)).value();

        if (exists) {
            logger.info("Index {} already exists", indexName);
            return;
        }

        // Create index with mappings
        CreateIndexResponse response = client.indices().create(c -> c
                .index(indexName)
                .settings(s -> s
                        .numberOfShards("3")
                        .numberOfReplicas("1")
                        .analysis(a -> a
                                .analyzer("vietnamese", an -> an
                                        .custom(ca -> ca
                                                .tokenizer("standard")
                                                .filter("lowercase", "asciifolding")))))
                .mappings(m -> m
                        .properties("id", p -> p.keyword(k -> k))
                        .properties("url", p -> p.keyword(k -> k))
                        .properties("title", p -> p.text(t -> t.analyzer("vietnamese")))
                        .properties("description", p -> p.text(t -> t.analyzer("vietnamese")))
                        .properties("content", p -> p.text(t -> t.analyzer("vietnamese")))
                        .properties("author", p -> p.keyword(k -> k))
                        .properties("source", p -> p.keyword(k -> k))
                        .properties("category", p -> p.keyword(k -> k))
                        .properties("tags", p -> p.keyword(k -> k))
                        .properties("imageUrl", p -> p.keyword(k -> k))
                        .properties("publishTime", p -> p.date(d -> d))
                        .properties("crawlTime", p -> p.date(d -> d))
                        .properties("language", p -> p.keyword(k -> k))
                        .properties("wordCount", p -> p.integer(i -> i))
                        .properties("clusterId", p -> p.keyword(k -> k))
                        .properties("trendingScore", p -> p.float_(f -> f))
                        .properties("embedding", p -> p.denseVector(dv -> dv
                                .dims(768)
                                .index(true)
                                .similarity("cosine")))));

        logger.info("Created index {}: acknowledged={}", indexName, response.acknowledged());
    }

    /**
     * Index a single article
     */
    public void indexArticle(Article article) throws IOException {
        IndexResponse response = client.index(i -> i
                .index(indexName)
                .id(article.getId())
                .document(article));

        logger.debug("Indexed article {}: result={}", article.getId(), response.result());
    }

    /**
     * Bulk index multiple articles
     */
    public void bulkIndexArticles(List<Article> articles) throws IOException {
        if (articles.isEmpty()) {
            return;
        }

        List<BulkOperation> operations = articles.stream()
                .map(article -> BulkOperation.of(op -> op
                        .index(idx -> idx
                                .index(indexName)
                                .id(article.getId())
                                .document(article))))
                .toList();

        BulkResponse response = client.bulk(b -> b.operations(operations));

        if (response.errors()) {
            logger.error("Bulk indexing had errors");
            response.items().stream()
                    .filter(item -> item.error() != null)
                    .forEach(item -> logger.error("Error: {}", item.error().reason()));
        } else {
            logger.info("Bulk indexed {} articles in {}ms", articles.size(), response.took());
        }
    }

    /**
     * Update trending score for an article
     */
    public void updateTrendingScore(String articleId, double trendingScore) throws IOException {
        client.update(u -> u
                .index(indexName)
                .id(articleId)
                .doc(Map.of("trendingScore", trendingScore)),
                Article.class);
    }

    /**
     * Delete old articles
     */
    public void deleteOldArticles(long olderThanMillis) throws IOException {
        DeleteByQueryResponse response = client.deleteByQuery(d -> d
                .index(indexName)
                .query(q -> q
                        .range(r -> r
                                .field("crawlTime")
                                .lt(co.elastic.clients.json.JsonData.of(olderThanMillis)))));

        logger.info("Deleted {} old articles", response.deleted());
    }

    /**
     * Get index statistics
     */
    public long getDocumentCount() throws IOException {
        return client.count(c -> c.index(indexName)).count();
    }

    @Override
    public void close() throws IOException {
        transport.close();
        restClient.close();
        logger.info("Elasticsearch client closed");
    }
}
