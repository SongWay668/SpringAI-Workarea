package com.ws16289.daxi.repository.impl;

import com.ws16289.daxi.repository.OpenSearchStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.opensearch.OpenSearchVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenSearch 向量存储 Repository 实现类，依赖OpenSearchAPI
 * 负责向量数据库的底层 CRUD 操作
 */
@Slf4j
@Repository
@DependsOn("vectorStore") // 确保在 Spring AI 默认的 VectorStore 初始化之后
public class OpenSearchStoreRepositoryImpl implements OpenSearchStoreRepository {

    @Autowired
    private OpenSearchClient openSearchClient;

    @Autowired
    private org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private VectorStore defaultVectorStore;

//    @Value("${spring.ai.vectorstore.opensearch.initialize-schema:false}")
//    private boolean initializeSchema;
//    // Spring AI 默认的 index 名称
//    private static final String DEFAULT_INDEX_NAME = "spring-ai-document-index";

    // 缓存已创建的 VectorStore
    private final Map<String, VectorStore> vectorStoreMap = new ConcurrentHashMap<>();

    // 已存在的 index 集合
    private final Set<String> existingIndexes = ConcurrentHashMap.newKeySet();

    /**
     * 初始化时加载所有已存在的索引到缓存
     */
    @PostConstruct
    public void init() {
        try {
            // 验证 OpenSearch 连接
            openSearchClient.info();
            log.info("OpenSearch connection verified");

            // 获取所有索引（过滤内部索引）
            var allIndexes = getAllIndexes();
            log.info("Found {} indexes in OpenSearch", allIndexes.size());

            for (String indexName : allIndexes) {
                if (!vectorStoreMap.containsKey(indexName)) {
                    try {
                        OpenSearchVectorStore openSearchVectorStore = OpenSearchVectorStore.builder(
                                openSearchClient, embeddingModel
                        )
                                .initializeSchema(false)
                                .index(indexName)
                                .build();

                        vectorStoreMap.put(indexName, openSearchVectorStore);
                        existingIndexes.add(indexName);
                        log.info("Cached VectorStore for index: {}", indexName);
                    } catch (Exception e) {
                        log.warn("Failed to cache VectorStore for index '{}': {}", indexName, e.getMessage());
                    }
                }
            }

            log.info("Initialization complete. Cached {} VectorStores", vectorStoreMap.size());
        } catch (IOException e) {
            log.warn("OpenSearch not available or failed to initialize: {}", e.getMessage());
            log.info("VectorStore caching will be deferred until OpenSearch is available");
        }
    }

    @Override
    public VectorStore createVectorStore(String indexName) {
        String normalizedName = normalizeIndexName(indexName);

        // 验证 OpenSearch 连接
        try {
            openSearchClient.info();
            log.info("OpenSearch connection verified");
        } catch (IOException e) {
            log.error("Cannot connect to OpenSearch", e);
            throw new RuntimeException("Cannot connect to OpenSearch: " + e.getMessage(), e);
        }

        if (vectorStoreMap.containsKey(normalizedName)) {
            log.info("VectorStore: '{}' exists, returning cached instance", normalizedName);
            return vectorStoreMap.get(normalizedName);
        }
        //此时schema不会真正创建
        OpenSearchVectorStore openSearchVectorStore = OpenSearchVectorStore.builder(openSearchClient, embeddingModel)
                .initializeSchema(true)
                .index(normalizedName)
                .build();

        // 添加空文档触发 schema 创建
        try {
            org.springframework.ai.document.Document doc = new org.springframework.ai.document.Document("init");
            openSearchVectorStore.add(java.util.List.of(doc));
            // schema 创建成功后立即删除临时文档，此后schema 创建但不可读，直到上传新的文档
            openSearchClient.delete(d -> d.index(normalizedName).id("init"));
            log.info("Schema created for index: {}", normalizedName);
        } catch (Exception e) {
            log.warn("Failed to trigger schema creation with temp document: {}", e.getMessage());
        }

        vectorStoreMap.put(normalizedName, openSearchVectorStore);
        existingIndexes.add(normalizedName);
        log.info("A new VectorStore for {} created", normalizedName);
        return openSearchVectorStore;
    }

    @Override
    public boolean deleteIndex(String indexName) throws IOException {
        String normalizedName = normalizeIndexName(indexName);
        try {
            boolean deleted = openSearchClient.indices().delete(d -> d.index(normalizedName)).acknowledged();

            if (deleted) {
                // 从缓存中移除
                vectorStoreMap.remove(normalizedName);
                existingIndexes.remove(normalizedName);
                log.info("Deleted index: {}", normalizedName);
            }

            return deleted;
        } catch (IOException | OpenSearchException e) {
            log.error("Failed to delete index: {}", normalizedName, e);
            throw new IOException(e);
        }
    }

    @Override
    public boolean indexExists(String indexName) {
        String normalizedName = normalizeIndexName(indexName);

        try {
            // 先验证连接
            openSearchClient.info();
            return openSearchClient.indices().exists(ExistsRequest.of(e -> e.index(normalizedName))).value();
        } catch (IOException e) {
            log.error("Failed to check if index exists: {}", normalizedName, e);
            return false;
        }
    }

    @Override
    public VectorStore getVectorStore(String indexName) {
        String normalizedName = normalizeIndexName(indexName);

        // 从缓存中获取
        if (vectorStoreMap.containsKey(normalizedName)) {
            log.info("从缓存获取 VectorStore: {}", normalizedName);
            return vectorStoreMap.get(normalizedName);
        }

        // 如果缓存中没有，检查索引是否存在
        if (existingIndexes.contains(normalizedName)) {
            log.warn("索引 '{}' 存在于 OpenSearch 但不在缓存中，尝试创建 VectorStore", normalizedName);
            return createVectorStore(indexName);
        }

        log.warn("索引 '{}' 不存在且未缓存", normalizedName);
        return null;
    }

    @Override
    public long getDocumentCount(String indexName) {
        String normalizedName = normalizeIndexName(indexName);

        try {
            // 先验证连接
            openSearchClient.info();

            // 使用 count API 查询索引中的文档数量
            var response = openSearchClient.count(c -> c.index(normalizedName));
            return response.count();
        } catch (IOException e) {
            log.error("Failed to get document count for index '{}': {}", normalizedName, e.getMessage());
            return -1;
        } catch (Exception e) {
            log.error("Failed to get document count for index '{}': {}", normalizedName, e.getMessage(), e);
            return -1;
        }
    }

    @Override
    public List<String> getAllIndexes() {
        try {
            // 实时查询 OpenSearch 获取所有索引
            var response = openSearchClient.cat().indices(i -> i);
            return response.valueBody().stream()
                    .map(record -> record.index())
                    // 过滤掉 OpenSearch 内部索引（以点开头或 top_queries 开头的）
                    .filter(index -> !index.startsWith(".") && !index.startsWith("top_queries"))
                    .toList();
        } catch (IOException e) {
            log.error("Failed to get indexes from OpenSearch", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取索引详情列表（包含创建时间）
     *
     * @return 索引详情列表
     */
    public List<IndexInfo> getIndexDetails() {
        try {
            var response = openSearchClient.cat().indices(i -> i);
            return response.valueBody().stream()
                    .filter(record -> {
                        String indexName = record.index();
                        // 过滤掉 OpenSearch 内部索引
                        return !indexName.startsWith(".") && !indexName.startsWith("top_queries");
                    })
                    .map(record -> {
                        String creationDate = "N/A";

                        // 尝试多个字段获取创建时间
                        if (record.creationDate() != null && !record.creationDate().isEmpty()) {
                            creationDate = record.creationDate();
                        } else if (record.creationDateString() != null && !record.creationDateString().isEmpty()) {
                            creationDate = record.creationDateString();
                        }

                        // 调试：打印第一个索引的可用字段
                        if (log.isDebugEnabled() && "N/A".equals(creationDate)) {
                            log.debug("Index: {}, creationDate: {}, creationDateString: {}",
                                    record.index(), record.creationDate(), record.creationDateString());
                        }

                        return new IndexInfo(record.index(), creationDate);
                    })
                    .toList();
        } catch (IOException e) {
            log.error("Failed to get index details from OpenSearch", e);
            return new ArrayList<>();
        }
    }

    @Override
    public void testConnection() throws IOException {
        openSearchClient.info();
    }

    /**
     * 索引信息
     */
    public record IndexInfo(String indexName, String creationDate) {
    }

    @Override
    public Map<String, VectorStore> getAllVectorStores() {
        return new HashMap<>(vectorStoreMap);
    }

    /**
     * 规范化 index 名称
     * 只允许小写字母、数字、连字符和下划线
     *
     * @param indexName 原始 index 名称
     * @return 规范化后的名称
     */
    private String normalizeIndexName(String indexName) {
        if (indexName == null || indexName.trim().isEmpty()) {
            throw new IllegalArgumentException("Index name cannot be null or empty");
        }

        // 转小写
        String normalized = indexName.toLowerCase().trim();

        // 替换非法字符
        normalized = normalized.replaceAll("[^a-z0-9_-]", "-");

        // 确保不以连字符或下划线开头
        normalized = normalized.replaceAll("^[-_]+", "");

        // 限制长度
        if (normalized.length() > 255) {
            normalized = normalized.substring(0, 255);
        }

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Invalid index name: " + indexName);
        }

        return normalized;
    }
}
