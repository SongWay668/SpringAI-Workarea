package com.ws16289.daxi.service.impl.ai;

import com.ws16289.daxi.service.ai.IAiQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.vectorstore.filter.Filter;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiQueryServiceImpl implements  IAiQueryService{
    @Autowired
    private ChatModel chatModel;

    @Autowired
    private OpenSearchStoreService openSearchStoreService;


    @Autowired
    private RewriteQueryTransformer rewriteQueryTransformer;

    @Autowired
    private CompressionQueryTransformer compressionQueryTransformer;

    @Autowired
    private MultiQueryExpander multiQueryExpander;

    @Autowired
    private QueryAugmenter queryAugmenter;

    @Value("${embedding.retrieval.top-k:3}")
    private int defaultTopK;

    @Value("${embedding.retrieval.similarity-threshold:0.2}")
    private double defaultSimilarityThreshold;

    @Value("${embedding.retrieval.filter.category:}")
    private String defaultCategory;

    @Value("${embedding.retrieval.filter.is-active:}")
    private Boolean defaultIsActive;

    // VectorStore 缓存，避免重复获取
    private final Map<String, VectorStore> vectorStoreCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${embedding.retrieval.enable-trans:false}")
    private Boolean enableTransFormer;
    @Value("${embedding.retrieval.filter.enable-expander:false}")
    private Boolean enableQueryExpander;
    @Value("${embedding.retrieval.enable-retriever:false}")
    private Boolean enableDocRetriever;
    @Value("${embedding.retrieval.filter.enable-queryaug:false}")
    private Boolean enableQueryAugmenter;
    @Value("${embedding.retrieval.filter.enable-postprocessor:false}")
    private Boolean enablePostProcessors;

    public Flux<String> advanceRag(ChatClient chatClient, String vectorStoreName, String query, String memoryId ){

        if(! this.isVectorStoreValid(vectorStoreName)) {
            return Flux.empty();
        }
        if(!enableTransFormer && !enableDocRetriever && !enableQueryAugmenter & !enablePostProcessors){
            return chat(chatClient,query,memoryId,null);
        }
        VectorStore vectorStore = getVectorStore(vectorStoreName);
        var retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder();
        if(enableTransFormer){
            retrievalAugmentationAdvisor.queryTransformers(RewriteQueryTransformer.builder()
                            .chatClientBuilder(ChatClient.builder(chatModel).build().mutate())
                            .build())
                    .queryTransformers(CompressionQueryTransformer.builder()
                            .chatClientBuilder(ChatClient.builder(chatModel).build().mutate())
                            .build());
        }
        if(enableQueryExpander){
            retrievalAugmentationAdvisor.queryExpander(MultiQueryExpander.builder()
                    .chatClientBuilder(ChatClient.builder(chatModel).build().mutate())
                    .numberOfQueries(3)
                    .includeOriginal(true)
                    .build());
        }
        if(enableDocRetriever) {
            //负责从底层数据源（如搜索引擎、向量存储、数据库或知识图谱）中检索文档的组件。这个插件与QuestionAnswerAdvisor实现功能基本一致
            retrievalAugmentationAdvisor.documentRetriever(VectorStoreDocumentRetriever.builder()
                    .similarityThreshold(defaultSimilarityThreshold)
                    .topK(defaultTopK)
                    .vectorStore(vectorStore)
                    .filterExpression(Objects.requireNonNull(buildFilterExpression(defaultCategory,defaultIsActive)))
                    .build());
        }
        if(enableQueryExpander) {
            //用于将根据多个查询从多个数据源检索到的文档组合成一个单一文档集合的组件。在组合过程中，它还能处理重复文档以及互惠排序策略
            retrievalAugmentationAdvisor.documentJoiner(new ConcatenationDocumentJoiner());
        }
        if(enablePostProcessors){
            //检索后文档rerank,监控
            retrievalAugmentationAdvisor.documentPostProcessors((temp, documents) -> {
                log.info("Original query: " + temp.text());
                log.info("Retrieved documents: " + documents.size());
                return documents;
            });
        }


        if(enableQueryAugmenter){
            //一种用于为输入查询添加额外数据的组件，有助于为大型语言模型提供必要的背景信息，从而能够回答用户的问题

            retrievalAugmentationAdvisor.queryAugmenter(ContextualQueryAugmenter.builder().promptTemplate(
                    new PromptTemplate("""
                        以下为相关背景信息。
                        ---------------------
                        {context}
                        ---------------------
                        
                        根据提供的背景信息且没有先入为主的观念，回答问题。
                        请遵循以下规则：
                        1. 如果答案不在所提供的信息中，那就直接说你不知道。
                        2. 避免使用诸如“根据上下文……”或“所提供的信息……”这样的表述。
                        查询：{query}
                        回答：
                        """))
                    .allowEmptyContext(true)
                    .build());
        }
                BaseAdvisor advisor = retrievalAugmentationAdvisor.build();



        // 重排序
//        RetrievalRerankAdvisor retrievalRerankAdvisor = new RetrievalRerankAdvisor(
//                vectorStore,
//                dashScopeRerankModel,
//                SearchRequest.builder()
//                        .topK(defaultTopK)// 第一阶段检索 defaultTopK 个候选文档,第二阶段会根据topn筛选
//                        .build()
//        );

        return chat(chatClient,query,memoryId,advisor);

    }

    @Override
    public Flux<String> queryWithRewriteTransformer(ChatClient chatClient, String vectorStoreName, String query
            , String memoryId){
        log.info("开始查询 - VectorStoreName: {}, Query: {}", vectorStoreName, query);

        try {

            if(this.isVectorStoreValid(vectorStoreName)){

                VectorStore vectorStore = getVectorStore(vectorStoreName);
                log.info("RewriteQueryTransformer 注入成功");
                DocumentRetriever retriever = createtDocumentRetriever(vectorStore);

                BaseAdvisor advisor = createAdvisor(retriever,rewriteQueryTransformer);

                log.info("准备开始 RAG 查询...");
                return chat(chatClient, query, memoryId, advisor);
            }else{
                return chatWithoutDocument(chatClient, query, memoryId);
            }

        } catch (org.opensearch.client.opensearch._types.OpenSearchException e) {
            log.error("OpenSearch 查询异常", e);
            throw new RuntimeException("OpenSearch 查询失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("查询处理异常", e);
            throw new RuntimeException("查询处理失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<String> queryWithCompressionTransformer(ChatClient chatClient, String vectorStoreName, String query
            , String memoryId){

        if(this.isVectorStoreValid(vectorStoreName)){
            VectorStore vectorStore = getVectorStore(vectorStoreName);
            log.info("CompressionQueryTransformer 注入成功");
            DocumentRetriever retriever = createtDocumentRetriever(vectorStore);

            BaseAdvisor advisor = createAdvisor(retriever,rewriteQueryTransformer,compressionQueryTransformer);
            return chat(chatClient, query, memoryId, advisor);
        }else{
            return chatWithoutDocument(chatClient,query,memoryId);
        }
    }
    @Override
    public Flux<String> queryWithRewriteThenCompression(ChatClient chatClient, String vectorStoreName, String query
            , String memoryId){

        if(this.isVectorStoreValid(vectorStoreName)){
            VectorStore vectorStore = getVectorStore(vectorStoreName);
            log.info("CompressionQueryTransformer 注入成功");
            DocumentRetriever retriever = createtDocumentRetriever(vectorStore);

            BaseAdvisor advisor = createAdvisor(retriever,compressionQueryTransformer);
            return chat(chatClient, query, memoryId, advisor);
        }else{
            return chatWithoutDocument(chatClient,query,memoryId);
        }
    }
    @Override
    public Flux<String> queryQueryExpander(ChatClient chatClient, String vectorStoreName, String query, String memoryId){
        log.info("MultiQueryExpander 注入成功");

        if(this.isVectorStoreValid(vectorStoreName)) {
            VectorStore vectorStore = getVectorStore(vectorStoreName);
            List<Query> queries = multiQueryExpander.apply(Query.builder().text(query).build());
            DocumentRetriever retriever = createtDocumentRetriever(vectorStore);

            // 并行检索所有查询的文档
            // 3. 并行检索，构建 Map<Query, List<Document>>
            Map<Query, List<Document>> retrievedDocsMap = queries.parallelStream()
                    .collect(Collectors.toMap(
                            q -> q,
                            q -> retriever.apply(q),
                            (existing, replacement) -> existing,
                            LinkedHashMap::new
                    ));

            // 4. 合并文档 - 构造 Map<Query, List<List<Document>>> 供 DocumentJoiner 使用
            Map<Query, List<List<Document>>> joinerInput = new LinkedHashMap<>();
            retrievedDocsMap.forEach((q, docs) -> {
                joinerInput.put(q, List.of(docs));
            });
            DocumentJoiner documentJoiner = new ConcatenationDocumentJoiner();
            List<Document> finalDocs = documentJoiner.join(joinerInput);
            log.info("MultiQuery 检索完成 - 扩展查询数: {}, 检索文档总数: {}",
                    queries.size(), finalDocs.size());

            // 使用带文档的 chat 方法
            return chatWithDocuments(chatClient, query, memoryId, finalDocs);
        }else{
            return chatWithoutDocument(chatClient,query,memoryId);
        }

    }

    /**
     * QueryAugmenter 使用示例：在检索后对查询进行增强
     */
    public Flux<String> queryWithQueryAugmenter(ChatClient chatClient, String vectorStoreName, String query, String memoryId){
        log.info("=== 开始 QueryAugmenter 查询 ===");
        log.info("原始查询: {}, VectorStore: {}", query, vectorStoreName);
        log.info("QueryAugmenter 注入成功");

        if(this.isVectorStoreValid(vectorStoreName)) {
            VectorStore vectorStore = getVectorStore(vectorStoreName);
            DocumentRetriever retriever = createtDocumentRetriever(vectorStore);

            // 1. 先检索文档
            log.info("步骤 1: 检索文档");
            List<Document> docs = retriever.apply(Query.builder().text(query).build());
            log.info("检索到 {} 个文档", docs.size());

            // 2. 使用 QueryAugmenter 根据上下文增强查询
            log.info("步骤 2: 使用 QueryAugmenter 增强查询");
            Query augmentedQuery = queryAugmenter.apply(
                    Query.builder().text(query).build(),
                    docs
            );

            log.info("原始查询: {}", query);
            log.info("增强后查询: {}", augmentedQuery.text());

            // 3. 使用增强后的查询生成回答（结合原始文档）
            log.info("步骤 3: 生成回答");
            return chatWithDocuments(chatClient, augmentedQuery.text(), memoryId, docs);
        }else{
            log.warn("VectorStore 无效，跳过 RAG 检索");
            return chatWithoutDocument(chatClient, query, memoryId);
        }
    }
    /**
     * All-In-One 查询：整合所有策略
     * 流程：Rewrite → Compression → MultiQuery扩展 → 检索 → QueryAugmenter增强 → 生成回答
     */
    @Override
    public Flux<String> allInOneQuery(ChatClient chatClient, String vectorStoreName, String query, String memoryId){
        log.info("=== 开始 All-In-One 查询 ===");
        log.info("原始查询: {}, VectorStore: {}", query, vectorStoreName);

        if(!this.isVectorStoreValid(vectorStoreName)){
            log.warn("VectorStore 无效，直接返回普通查询");
            return chatWithoutDocument(chatClient, query, memoryId);
        }

        // 步骤 1: Rewrite Query Transformer - 重写查询，优化语义
        log.info("步骤 1: Rewrite Query");
        log.info("RewriteQueryTransformer 注入成功");
        Query rewriteQuery = rewriteQueryTransformer.apply(Query.builder().text(query).build());
        log.info("重写后查询: {}", rewriteQuery.text());

        // 步骤 2: Compression Query Transformer - 压缩查询，去除冗余
        log.info("步骤 2: Compression Query");
        log.info("CompressionQueryTransformer 注入成功");
        Query compressionQuery = compressionQueryTransformer.transform(rewriteQuery);
        log.info("压缩后查询: {}", compressionQuery.text());

        // 步骤 3: MultiQuery Expander - 扩展为多个查询变体
        log.info("步骤 3: MultiQuery Expander");
        log.info("MultiQueryExpander 注入成功");
        List<Query> expandedQueries = multiQueryExpander.apply(compressionQuery);
        log.info("扩展后查询数量: {}", expandedQueries.size());

        // 步骤 4: 并行检索所有查询的文档
        log.info("步骤 4: 并行检索文档");
        VectorStore vectorStore = getVectorStore(vectorStoreName);
        DocumentRetriever retriever = createtDocumentRetriever(vectorStore);

        Map<Query, List<Document>> retrievedDocsMap = expandedQueries.parallelStream()
                .collect(Collectors.toMap(
                        q -> q,
                        q -> {
                            List<Document> docs = retriever.apply(q);
                            log.debug("  查询 '{}' 检索到 {} 个文档", q.text(), docs.size());
                            return docs;
                        },
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));

        // 步骤 5: 合并文档
        log.info("步骤 5: 合并文档");
        Map<Query, List<List<Document>>> joinerInput = new LinkedHashMap<>();
        retrievedDocsMap.forEach((q, docs) -> {
            joinerInput.put(q, List.of(docs));
        });
        DocumentJoiner documentJoiner = new ConcatenationDocumentJoiner();
        List<Document> finalDocs = documentJoiner.join(joinerInput);
        log.info("合并后文档数量: {}", finalDocs.size());

        // 步骤 6: QueryAugmenter - 根据检索到的文档上下文增强查询
        log.info("步骤 6: QueryAugmenter 增强");
        log.info("QueryAugmenter 注入成功");
        Query augmentedQuery = queryAugmenter.apply(
                Query.builder().text(query).build(),  // 使用原始查询
                finalDocs                              // 使用检索到的文档
        );
        log.info("增强后查询: {}", augmentedQuery.text());

        // 步骤 7: 使用增强后的查询和文档生成回答
        log.info("步骤 7: 生成回答");
        return chatWithDocuments(chatClient, augmentedQuery.text(), memoryId, finalDocs);
    }




    private  BaseAdvisor createAdvisor(DocumentRetriever retriever,QueryTransformer...queryTransformers) {
        BaseAdvisor advisor = RetrievalAugmentationAdvisor.builder()
                .queryTransformers(queryTransformers)
                .documentRetriever(retriever).build();
        log.info("RetrievalAugmentationAdvisor 创建成功");
        return advisor;
    }

    private  DocumentRetriever createtDocumentRetriever(VectorStore vectorStore) {
        // 创建 DocumentRetriever
        var builder = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(this.defaultTopK)
                .similarityThreshold(this.defaultSimilarityThreshold);

        // 添加过滤表达式（从配置文件读取）
        Filter.Expression filterExpression = buildFilterExpression(defaultCategory, defaultIsActive);
        if (filterExpression != null) {
            builder.filterExpression(filterExpression);
            log.info("DocumentRetriever 应用过滤条件: category={}, isActive={}",
                    defaultCategory, defaultIsActive);
        }

        DocumentRetriever retriever = builder.build();
        log.info("DocumentRetriever 创建成功");
        return retriever;
    }



    private Flux<String> chatWithoutDocument(ChatClient chatClient, String query, String memoryId) {
        return chat(chatClient,query,memoryId,null);
    }

    private Flux<String> chat(ChatClient chatClient, String query, String memoryId, BaseAdvisor advisor) {
        var client =  chatClient.prompt()
                .user(query)
                .advisors(a -> a.param("chat_memory_conversation_id", memoryId));
        if(null != advisor){
            client.advisors(advisor);
        }
        return client.stream().content();

    }

    private Flux<String> chatWithDocuments(ChatClient chatClient, String query, String memoryId, List<Document> finalDocs) {
        // 使用检索到的文档生成回答
        String ragPrompt = """
                    基于以下参考文档回答问题。如果文档中没有相关信息，请明确说明。
                    问题: {query}
                    参考文档:
                    {context}
                    请提供准确、简洁的回答：
                    """;
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < finalDocs.size(); i++) {
            Document doc = finalDocs.get(i);
            context.append("\n--- 文档 ").append(i + 1).append(" (相似度: ")
                    .append(doc.getMetadata().getOrDefault("distance", "N/A"))
                    .append(") ---\n");
            context.append(doc.getText());
            context.append("\n");
        }

        return chat(chatClient,ragPrompt
                .replace("{query}", query)
                .replace("{context}", context.toString()),memoryId,null);
    }

    private VectorStore getVectorStore(String vectorStoreName){
        // 先从缓存中获取
        if (vectorStoreCache.containsKey(vectorStoreName)) {
            log.debug("从缓存获取 VectorStore: {}", vectorStoreName);
            return vectorStoreCache.get(vectorStoreName);
        }

        // 缓存中没有，从 OpenSearchStoreService 获取
        VectorStore vectorStore = openSearchStoreService.getVectorStore(vectorStoreName);
        if (vectorStore != null) {
            vectorStoreCache.put(vectorStoreName, vectorStore);
            log.debug("VectorStore 已缓存: {}", vectorStoreName);
        }
        return vectorStore;
    }
    private boolean isVectorStoreValid(String vectorStore){
        // 检查索引是否存在
        return isVectorStoreExists(vectorStore) && hasContent(vectorStore);
    }
    private boolean isVectorStoreExists(String vectorStore){
        // 检查索引是否存在
        return openSearchStoreService.indexExists(vectorStore);
    }
    private boolean hasContent(String vectorStore){
        return openSearchStoreService.getDocumentCount(vectorStore) >0;
    }

    /**
     * 构建文档过滤表达式
     *
     * @param category 文档分类（可选）
     * @param isActive 是否只检索活跃文档（可选）
     * @return Filter.Expression 过滤表达式，如果两个参数都为空则返回 null
     */
    private Filter.Expression buildFilterExpression(String category, Boolean isActive) {
        // 收集所有有效的过滤条件
        java.util.List<Filter.Expression> expressions = new java.util.ArrayList<>();
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();
        // 添加 category 过滤条件
        if (category != null && !category.trim().isEmpty()) {
//            Filter.ExpressionType type, Operand left, Filter.Operand right
            expressions.add(filterExpressionBuilder.eq("category", category).build());
            log.debug("添加 category 过滤条件: {}", category);
        }

        // 添加 isActive 过滤条件
        if (isActive != null) {
            expressions.add(filterExpressionBuilder.eq("is_active", isActive).build());
            log.debug("添加 isActive 过滤条件: {}", isActive);
        }

        // 根据条件数量返回结果
        if (expressions.isEmpty()) {
            log.debug("没有配置过滤条件");
            return null;
        } else if (expressions.size() == 1) {
            return expressions.get(0);
        } else {
            // 多个条件使用 AND 连接
            Filter.Expression combined = expressions.get(0);
            for (int i = 1; i < expressions.size(); i++) {
                combined = new Filter.Expression(Filter.ExpressionType.AND, combined, expressions.get(i));
            }
            log.debug("组合过滤条件: {} 个条件", expressions.size());
            return combined;
        }
    }

}
