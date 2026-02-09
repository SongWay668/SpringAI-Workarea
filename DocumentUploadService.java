package com.ws16289.daxi.service.impl.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws16289.daxi.dto.DocumentUploadRequest;
import com.ws16289.daxi.util.splitter.IDocumentSplitter;
import com.ws16289.daxi.util.splitter.facotry.SplitterType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.ContentFormatTransformer;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 文档上传服务
 */
@Slf4j
@Service
public class DocumentUploadService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${document.split.cache.prefix:doc:split:}")
    private String cachePrefix;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private OpenSearchStoreService openSearchStoreService;

    @Autowired
    private com.ws16289.daxi.util.splitter.DocumentSplitterManager documentSplitterManager;

    @Autowired
    private ContentFormatTransformer contentFormatTransformer;

    @Autowired
    private KeywordMetadataEnricher keywordMetadataEnricher;

    @Autowired
    private SummaryMetadataEnricher summaryMetadataEnricher;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 预览文档分割（基于文件）
     *
     * @param file 上传的文件
     * @param splitterType 分割器类型
     * @param isActive 是否生效
     * @param validStartDate 生效开始日期
     * @param validEndDate 生效结束日期
     * @param uploader 上传者
     * @return 分割结果
     */
    public Map<String, Object> previewSplit(MultipartFile file, String splitterType, Boolean isActive,
                                            String validStartDate, String validEndDate, String uploader) {
        // 预览时最多返回的片段数
         final int MAX_PREVIEW_CHUNKS = 8;
         final int PREVIEW_TEXT_LENGTH = 500; // 每个片段预览的文本长度

        // 生成缓存key
        String cacheKey = cachePrefix + UUID.randomUUID().toString();

        // 获取文档分割器（默认使用 SPRING）
        IDocumentSplitter documentSplitter;
        if (splitterType == null || splitterType.trim().isEmpty()) {
            documentSplitter = documentSplitterManager.getSplitter(SplitterType.SPRING);
        } else {
            try {
                SplitterType type = SplitterType.valueOf(splitterType.toUpperCase());
                documentSplitter = documentSplitterManager.getSplitter(type);
            } catch (IllegalArgumentException e) {
                return Map.of("success", false, "message", "不支持的分割器类型: " + splitterType + ". 支持的类型: " + documentSplitterManager.getAvailableSplitters());
            }
        }

        // 临时保存文件
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("preview_", getFileExtension(file.getOriginalFilename()));
            file.transferTo(tempFile.toFile());
            log.info("临时文件保存成功: {}", tempFile);

            // 读取文件内容
            List<Document> documents = readFile(tempFile.toFile(), file.getOriginalFilename());

            if (documents.isEmpty()) {
                return Map.of("success", false, "message", "文件内容为空或读取失败");
            }

            // 分割文档
            List<Document> splitDocuments = documentSplitter.split(documents);

            List<Document> decoratedDocuments = splitDocuments;

            // 元数据丰富
    //            List<Document> decoratedDocuments = summaryMetadataEnricher.apply(
    //                    keywordMetadataEnricher.apply(
    //                            contentFormatTransformer.apply(
    //                                    splitDocuments
    //                            )
    //                    )
    //            );

            // 解析生效日期
            LocalDateTime validStartDateTime = parseValidStartDate(validStartDate);
            LocalDateTime validEndDateTime = parseValidEndDate(validEndDate);

            // 为每个Document添加元数据
            decoratedDocuments.forEach(doc -> {
                doc.getMetadata().put("file_name", file.getOriginalFilename());
                doc.getMetadata().put("is_active", isActive);
                doc.getMetadata().put("valid_from_date", validStartDateTime.format(DATE_FORMATTER));
                doc.getMetadata().put("valid_end_date", validEndDateTime.format(DATE_FORMATTER));
                if (uploader != null && !uploader.isEmpty()) {
                    doc.getMetadata().put("uploader", uploader);
                }
                doc.getMetadata().put("upload_time", LocalDateTime.now().format(DATE_FORMATTER));
            });

            // 将完整的分割结果缓存到 Redis
            List<Map<String, Object>> fullDocumentsData = new ArrayList<>();
            for (Document doc : decoratedDocuments) {
                Map<String, Object> docData = new HashMap<>();
                docData.put("text", doc.getText());
                docData.put("metadata", doc.getMetadata());
                fullDocumentsData.add(docData);
            }

            // 缓存完整数据（60分钟过期）
            try {
                String cacheValue = objectMapper.writeValueAsString(fullDocumentsData);
                redisTemplate.opsForValue().set(cacheKey, cacheValue, 60, TimeUnit.MINUTES);
                log.info("已缓存分割结果到 Redis，key: {}, 片段数: {}", cacheKey, fullDocumentsData.size());
            } catch (Exception e) {
                log.warn("缓存分割结果到 Redis 失败，继续处理", e);
            }

            // 只返回预览数据给前端
            List<Map<String, Object>> previewDocumentsData = new ArrayList<>();
            for (Document doc : decoratedDocuments.stream().limit(MAX_PREVIEW_CHUNKS).toList()) {
                Map<String, Object> docData = new HashMap<>();
                String fullText = doc.getText();
                String previewText = fullText.length() > PREVIEW_TEXT_LENGTH
                        ? fullText.substring(0, PREVIEW_TEXT_LENGTH) + "..."
                        : fullText;
                docData.put("text", previewText); // 只返回预览文本
                docData.put("metadata", doc.getMetadata());
                previewDocumentsData.add(docData);
            }

            return Map.of(
                    "success", true,
                    "documentCount", decoratedDocuments.size(),
                    "previewCount", previewDocumentsData.size(),
                    "splitterType", documentSplitter.getClass().getSimpleName().replace("DocumentSplitter", ""),
                    "truncated", decoratedDocuments.size() > MAX_PREVIEW_CHUNKS,
                    "cacheKey", cacheKey,
                    "documents", previewDocumentsData
            );

        } catch (Exception e) {
            log.error("预览分割失败", e);
            return Map.of("success", false, "message", "预览分割失败: " + e.getMessage());
        } finally {
            // 删除临时文件
            if (tempFile != null && Files.exists(tempFile)) {
                try {
                    Files.delete(tempFile);
                    log.info("临时文件已删除: {}", tempFile);
                } catch (IOException e) {
                    log.warn("删除临时文件失败: {}", tempFile, e);
                }
            }
        }
    }


    /**
     * 保存已分割的文档到向量库（不重复读取和分割）
     *
     * @param cacheKey 缓存key
     * @param indexName 索引名称
     * @param fileName 文件名
     * @param isActive 是否生效
     * @param validStartDate 生效开始日期
     * @param validEndDate 生效结束日期
     * @param uploader 上传者
     * @return 保存结果
     */
    public Map<String, Object> saveDocuments(String cacheKey,
                                            String indexName, String fileName,
                                            Boolean isActive, String validStartDate,
                                            String validEndDate, String uploader) {
        // 从 Redis 获取完整的分割结果
        List<Map<String, Object>> documentsData;
        try {
            String cacheValue = redisTemplate.opsForValue().get(cacheKey);
            if (cacheValue == null || cacheValue.isEmpty()) {
                return Map.of("success", false, "message", "缓存已过期或不存在，请重新预览分割");
            }
            documentsData = objectMapper.readValue(cacheValue, new TypeReference<List<Map<String, Object>>>() {});
            log.info("从 Redis 获取到缓存数据，key: {}, 片段数: {}", cacheKey, documentsData.size());
        } catch (Exception e) {
            log.error("从 Redis 获取缓存数据失败", e);
            return Map.of("success", false, "message", "获取缓存数据失败: " + e.getMessage());
        }


//        public Map<String, Object> saveDocuments(List<Map<String, Object>> documentsData,
//                                                String indexName, String fileName,
//                                                Boolean isActive, String validStartDate,
//                                                String validEndDate, String uploader) {
        // 检查索引是否存在
        if (!openSearchStoreService.indexExists(indexName)) {
            return Map.of("success", false, "message", "索引 '" + indexName + "' 不存在，请先创建");
        }

        // 获取VectorStore实例
        VectorStore vectorStore;
        try {
            vectorStore = openSearchStoreService.createVectorStore(indexName);
        } catch (Exception e) {
            log.error("获取VectorStore实例失败: {}", indexName, e);
            return Map.of("success", false, "message", "获取VectorStore实例失败: " + e.getMessage());
        }

        try {
            // 解析生效日期
            LocalDateTime validStartDateTime = parseValidStartDate(validStartDate);
            LocalDateTime validEndDateTime = parseValidEndDate(validEndDate);

            // 将缓存的数据转换为Document对象
            List<Document> documents = new ArrayList<>();
            for (Map<String, Object> docData : documentsData) {
                String text = (String) docData.get("text");
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) docData.get("metadata");

                Document document = new Document(text, new HashMap<>(metadata));

                // 更新元数据
                document.getMetadata().put("file_name", fileName);
                document.getMetadata().put("is_active", isActive);
                document.getMetadata().put("valid_from_date", validStartDateTime.format(DATE_FORMATTER));
                document.getMetadata().put("valid_end_date", validEndDateTime.format(DATE_FORMATTER));
                if (uploader != null && !uploader.isEmpty()) {
                    document.getMetadata().put("uploader", uploader);
                }
                document.getMetadata().put("upload_time", LocalDateTime.now().format(DATE_FORMATTER));

                documents.add(document);
            }

            log.info("准备保存 {} 个文档到索引: {}", documents.size(), indexName);

//            List<Document> decoratedDocuments = summaryMetadataEnricher.apply(
//                    keywordMetadataEnricher.apply(
//                            contentFormatTransformer.apply(documents)
//                    )
//            );

            // 写入向量库
            vectorStore.add(documents);
            log.info("成功写入 {} 个向量到索引: {}", documents.size(), indexName);

            // 保存成功后删除缓存
            try {
                redisTemplate.delete(cacheKey);
                log.info("已删除缓存，key: {}", cacheKey);
            } catch (Exception e) {
                log.warn("删除缓存失败", e);
            }

            return Map.of(
                    "success", true,
                    "documentCount", documents.size(),
                    "fileName", fileName,
                    "indexName", indexName
            );

        } catch (Exception e) {
            log.error("保存文档失败", e);
            return Map.of("success", false, "message", "保存文档失败: " + e.getMessage());
        }
    }

    /**
     * 上传文档到向量库
     *
     * @param request 上传请求
     * @return 上传结果
     */
    public Map<String, Object> uploadDocument(DocumentUploadRequest request) {
        MultipartFile file = request.getFile();
        String indexName = request.getIndexName();
        Boolean isActive = request.getIsActive() != null ? request.getIsActive() : true;
        String validStartDate = request.getValidStartDate();
        String validEndDate = request.getValidEndDate();
        String uploader = request.getUploader();
        String splitterType = request.getSplitterType();
        Boolean previewOnly = request.getPreviewOnly() != null ? request.getPreviewOnly() : false;


        IDocumentSplitter documentSplitter;
        if (splitterType == null || splitterType.trim().isEmpty()) {
            documentSplitter = documentSplitterManager.getSplitter(SplitterType.SPRING);
        } else {
            try {
                SplitterType type = SplitterType.valueOf(splitterType.toUpperCase());
                documentSplitter = documentSplitterManager.getSplitter(type);
            } catch (IllegalArgumentException e) {
                return Map.of("success", false, "message", "不支持的分割器类型: " + splitterType + ". 支持的类型: " + documentSplitterManager.getAvailableSplitters());
            }
        }


        if (!previewOnly) {
            if (!openSearchStoreService.indexExists(indexName)) {
                return Map.of("success", false, "message", "索引 '" + indexName + "' 不存在，请先创建");
            }
        }

        VectorStore vectorStore = null;
        if (!previewOnly) {
            try {
                vectorStore = openSearchStoreService.createVectorStore(indexName);
            } catch (Exception e) {
                log.error("获取VectorStore实例失败: {}", indexName, e);
                return Map.of("success", false, "message", "获取VectorStore实例失败: " + e.getMessage());
            }
        }

        // 临时保存文件
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("upload_", getFileExtension(file.getOriginalFilename()));
            file.transferTo(tempFile.toFile());
            log.info("临时文件保存成功: {}", tempFile);

            // 读取文件内容
            List<Document> documents = readFile(tempFile.toFile(), file.getOriginalFilename());

            if (documents.isEmpty()) {
                return Map.of("success", false, "message", "文件内容为空或读取失败");
            }

            List<Document> splitDocuments = documentSplitter.split(documents);

//            List<Document> decoratedDocuments = summaryMetadataEnricher.apply(
//                    keywordMetadataEnricher.apply(
//                            contentFormatTransformer.apply(
//                                    splitDocuments
//                            )
//                    )
//            );

            log.info("原始文档数: {}, 分段后文档数: {}", documents.size(), splitDocuments.size());

            // 解析生效日期
            LocalDateTime validStartDateTime = parseValidStartDate(validStartDate);
            LocalDateTime validEndDateTime = parseValidEndDate(validEndDate);

            // 为每个Document添加元数据
            splitDocuments.forEach(document -> {
                document.getMetadata().put("file_name", file.getOriginalFilename());
                document.getMetadata().put("is_active", isActive);
                document.getMetadata().put("valid_from_date", validStartDateTime.format(DATE_FORMATTER));
                document.getMetadata().put("valid_end_date", validEndDateTime.format(DATE_FORMATTER));
                if (uploader != null && !uploader.isEmpty()) {
                    document.getMetadata().put("uploader", uploader);
                }
                document.getMetadata().put("upload_time", LocalDateTime.now().format(DATE_FORMATTER));
            });

            // 预览模式：返回分片文档列表
            if (previewOnly) {
                List<Map<String, Object>> documentsData = new ArrayList<>();
                for (Document doc : splitDocuments) {
                    Map<String, Object> docData = new HashMap<>();
                    docData.put("text", doc.getText());
                    docData.put("metadata", doc.getMetadata());
                    documentsData.add(docData);
                }
                return Map.of(
                        "success", true,
                        "documentCount", splitDocuments.size(),
                        "fileName", file.getOriginalFilename(),
                        "splitterType", documentSplitter.getClass().getSimpleName().replace("DocumentSplitter", ""),
                        "documents", documentsData
                );
            }

            // 写入向量库
            vectorStore.add(splitDocuments);
            log.info("成功写入 {} 个向量到索引: {}", splitDocuments.size(), indexName);

            return Map.of(
                    "success", true,
                    "documentCount", splitDocuments.size(),
                    "fileName", file.getOriginalFilename()
            );

        } catch (Exception e) {
            log.error("文档处理失败: {}", file.getOriginalFilename(), e);
            return Map.of("success", false, "message", "文档处理失败: " + e.getMessage());
        } finally {
            // 删除临时文件
            if (tempFile != null && Files.exists(tempFile)) {
                try {
                    Files.delete(tempFile);
                    log.info("临时文件已删除: {}", tempFile);
                } catch (IOException e) {
                    log.warn("删除临时文件失败: {}", tempFile, e);
                }
            }
        }
    }

    /**
     * 读取文件并转换为Document列表
     *
     * @param file 文件
     * @param filename 原始文件名
     * @return Document列表
     */
    private List<Document> readFile(File file, String filename) {
        String ext = getFileExtension(filename).toLowerCase();

        try {
            if (ext.equals(".pdf")) {
                // 读取PDF文件
                org.springframework.core.io.Resource resource = new org.springframework.core.io.FileSystemResource(file);
                PagePdfDocumentReader reader = new PagePdfDocumentReader(
                        resource,
                        PdfDocumentReaderConfig.builder()
                                .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                                .withPagesPerDocument(1)
                                .build()
                );
                List<Document> documents = reader.read();
                log.info("读取PDF文件: {}, 文档片段数: {}", filename, documents.size());
                return documents;

            } else if (ext.equals(".txt")) {
                // 读取TXT文件
                org.springframework.core.io.Resource resource = new org.springframework.core.io.FileSystemResource(file);
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                List<Document> documents = reader.read();
                log.info("读取TXT文件: {}, 文档片段数: {}", filename, documents.size());
                return documents;

            } else if (ext.equals(".md")) {
                // 读取Markdown文件
                org.springframework.core.io.Resource resource = new org.springframework.core.io.FileSystemResource(file);
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                List<Document> documents = reader.read();
                log.info("读取Markdown文件: {}, 文档片段数: {}", filename, documents.size());
                return documents;

            } else if (ext.equals(".docx") || ext.equals(".doc")) {
                // 读取Word文件
                org.springframework.core.io.Resource resource = new org.springframework.core.io.FileSystemResource(file);
                TikaDocumentReader reader = new TikaDocumentReader(resource);
                List<Document> documents = reader.read();
                log.info("读取Word文件: {}, 文档片段数: {}", filename, documents.size());
                return documents;

            } else {
                log.warn("不支持的文件类型: {}", ext);
                return List.of();
            }

        } catch (Exception e) {
            log.error("读取文件失败: {}", filename, e);
            return List.of();
        }
    }

    /**
     * 解析生效开始日期
     *
     * @param validStartDate 日期字符串
     * @return LocalDateTime
     */
    private LocalDateTime parseValidStartDate(String validStartDate) {
        if (validStartDate == null || validStartDate.trim().isEmpty()) {
            // 默认当前时间
            return LocalDateTime.now();
        }

        try {
            return LocalDateTime.parse(validStartDate, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("日期格式错误: {}, 使用当前时间", validStartDate);
            return LocalDateTime.now();
        }
    }

    /**
     * 解析生效结束日期
     *
     * @param validEndDate 日期字符串
     * @return LocalDateTime
     */
    private LocalDateTime parseValidEndDate(String validEndDate) {
        if (validEndDate == null || validEndDate.trim().isEmpty()) {
            // 默认2099年12月31日
            return LocalDateTime.of(2099, 12, 31, 23, 59, 59);
        }

        try {
            return LocalDateTime.parse(validEndDate, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("日期格式错误: {}, 使用默认值", validEndDate);
            return LocalDateTime.of(2099, 12, 31, 23, 59, 59);
        }
    }

    /**
     * 获取文件扩展名
     *
     * @param filename 文件名
     * @return 扩展名（包含点号）
     */
    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }

        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }

        return filename.substring(lastDotIndex);
    }

    /**
     * 获取所有可用的向量库索引列表
     *
     * @return 索引名称列表
     */
    public List<String> getAvailableIndexes() {
        return openSearchStoreService.getAllIndexes();
    }
}
