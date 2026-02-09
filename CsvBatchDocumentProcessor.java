package com.ws16289.daxi.service.impl.ai;

import com.ws16289.daxi.util.splitter.IDocumentSplitter;
import com.ws16289.daxi.util.splitter.DocumentSplitterManager;
import com.ws16289.daxi.util.splitter.facotry.SplitterType;
import com.ws16289.daxi.util.splitter.facotry.SplitterFactoryProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生产者消费者模式的批量文档处理服务
 */
@Slf4j
@Service
public class CsvBatchDocumentProcessor {

    @Autowired
    private OpenSearchStoreService openSearchStoreService;

    @Autowired
    private DocumentSplitterManager documentSplitterManager;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 任务结果
     */
    public static class ProcessResult {
        private final int totalTasks;
        private final int successCount;
        private final int failCount;
        private final Map<String, String> errors;

        public ProcessResult(int totalTasks, int successCount, int failCount, Map<String, String> errors) {
            this.totalTasks = totalTasks;
            this.successCount = successCount;
            this.failCount = failCount;
            this.errors = errors;
        }

        public int getTotalTasks() {
            return totalTasks;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailCount() {
            return failCount;
        }

        public Map<String, String> getErrors() {
            return errors;
        }

        @Override
        public String toString() {
            return String.format("处理完成 - 总任务数: %d, 成功: %d, 失败: %d", totalTasks, successCount, failCount);
        }
    }

    /**
     * 处理任务
     */
    private static class ProcessTask {
        final String fileName;
        final String indexName;
        final SplitterType splitterType;
        final Boolean isActive;
        final String validStartDate;
        final String validEndDate;
        final File baseDir;

        ProcessTask(String fileName, String indexName, String splitterType,
                   Boolean isActive, String validStartDate, String validEndDate, File baseDir) {
            this.fileName = fileName;
            this.indexName = indexName;
            this.splitterType = SplitterType.valueOf(splitterType.toUpperCase());
            this.isActive = isActive;
            this.validStartDate = validStartDate;
            this.validEndDate = validEndDate;
            this.baseDir = baseDir;
        }
    }

    /**
     * 从 CSV 文件处理文档
     *
     * @param csvPath CSV 文件路径
     * @return 处理结果
     */
    public ProcessResult processFromCsv(String csvPath) {
        return processFromCsv(csvPath, 3); // 默认3个消费者线程
    }

    /**
     * 从 CSV 文件处理文档
     *
     * @param csvPath CSV 文件路径
     * @param consumerCount 消费者线程数
     * @return 处理结果
     */
    public ProcessResult processFromCsv(String csvPath, int consumerCount) {
        BlockingQueue<ProcessTask> taskQueue = new LinkedBlockingQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        Map<String, String> errors = new ConcurrentHashMap<>();
        AtomicInteger totalTasks = new AtomicInteger(0);

        File csvFile = new File(csvPath);
        if (!csvFile.exists()) {
            errors.put(csvPath, "CSV 文件不存在: " + csvPath);
            return new ProcessResult(0, 0, 1, errors);
        }

        File baseDir = csvFile.getParentFile();

        // 创建线程池
        ExecutorService executorService = Executors.newFixedThreadPool(consumerCount + 1);
        CountDownLatch completionLatch = new CountDownLatch(1);

        // 生产者任务：读取 CSV 并将任务放入队列
        Future<?> producerFuture = executorService.submit(() -> {
            try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
                String line;
                int lineNumber = 0;
                while ((line = br.readLine()) != null) {
                    lineNumber++;
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }

                    String[] parts = line.split(",");
                    if (parts.length < 6) {
                        String errorMsg = String.format("第 %d 行: 格式错误, 需要至少6列 (文档名,VectorStore Schema,SplitStrategy,是否生效,生效起始时间,生效截至时间)", lineNumber);
                        log.error(errorMsg);
                        errors.put("line_" + lineNumber, errorMsg);
                        failCount.incrementAndGet();
                        continue;
                    }

                    try {
                        String fileName = parts[0].trim();
                        String indexName = parts[1].trim();
                        String splitterType = parts[2].trim();
                        Boolean isActive = Integer.parseInt(parts[3].trim()) == 1;
                        String validStartDate = parts[4].trim();
                        String validEndDate = parts[5].trim();

                        // 验证文件是否存在
                        File docFile = new File(baseDir, fileName);
                        if (!docFile.exists()) {
                            String errorMsg = String.format("第 %d 行: 文档文件不存在: %s", lineNumber, docFile.getAbsolutePath());
                            log.error(errorMsg);
                            errors.put(fileName, errorMsg);
                            failCount.incrementAndGet();
                            continue;
                        }

                        // 验证索引是否存在
                        if (!openSearchStoreService.indexExists(indexName)) {
                            String errorMsg = String.format("第 %d 行: VectorStore 索引不存在: %s", lineNumber, indexName);
                            log.error(errorMsg);
                            errors.put(fileName, errorMsg);
                            failCount.incrementAndGet();
                            continue;
                        }

                        ProcessTask task = new ProcessTask(fileName, indexName, splitterType,
                                isActive, validStartDate, validEndDate, baseDir);
                        taskQueue.put(task);
                        totalTasks.incrementAndGet();
                        log.info("生产者添加任务: {} -> {}", fileName, indexName);

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("生产者线程被中断", e);
                        break;
                    } catch (Exception e) {
                        String errorMsg = String.format("第 %d 行: 处理失败 - %s", lineNumber, e.getMessage());
                        log.error(errorMsg, e);
                        errors.put("line_" + lineNumber, errorMsg);
                        failCount.incrementAndGet();
                    }
                }
                log.info("生产者完成，共生成 {} 个任务", totalTasks.get());

            } catch (IOException e) {
                log.error("读取 CSV 文件失败", e);
                errors.put(csvPath, "读取 CSV 文件失败: " + e.getMessage());
            } finally {
                completionLatch.countDown();
            }
        });

        // 消费者任务池
        List<Future<?>> consumerFutures = new ArrayList<>();
        for (int i = 0; i < consumerCount; i++) {
            final int consumerId = i + 1;
            Future<?> consumerFuture = executorService.submit(() -> {
                log.info("消费者 {} 启动", consumerId);
                try {
                    while (true) {
                        try {
                            // 使用 poll 等待生产者完成或超时
                            ProcessTask task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                            
                            if (task == null) {
                                // 检查生产者是否完成且队列为空
                                if (completionLatch.getCount() == 0 && taskQueue.isEmpty()) {
                                    log.info("消费者 {} 退出", consumerId);
                                    break;
                                }
                                continue;
                            }

                            try {
                                processTask(task);
                                successCount.incrementAndGet();
                                log.info("消费者 {} 成功处理: {} -> {}", consumerId, task.fileName, task.indexName);
                            } catch (Exception e) {
                                String errorMsg = String.format("处理失败: %s - %s", task.fileName, e.getMessage());
                                log.error(errorMsg, e);
                                errors.put(task.fileName, errorMsg);
                                failCount.incrementAndGet();
                            }

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.error("消费者 {} 被中断", consumerId, e);
                            break;
                        }
                    }
                } finally {
                    log.info("消费者 {} 结束", consumerId);
                }
            });
            consumerFutures.add(consumerFuture);
        }

        // 等待所有任务完成
        try {
            // 等待生产者完成
            producerFuture.get();
            log.info("生产者 Future 完成");

            // 等待所有消费者完成
            for (int i = 0; i < consumerCount; i++) {
                consumerFutures.get(i).get();
            }
            log.info("所有消费者 Future 完成");

        } catch (InterruptedException e) {
            log.error("主线程被中断", e);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("任务执行异常", e);
            errors.put("execution_error", "任务执行异常: " + e.getMessage());
        } finally {
            // 关闭线程池
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        return new ProcessResult(totalTasks.get(), successCount.get(), failCount.get(), errors);
    }

    /**
     * 处理单个任务
     */
    private void processTask(ProcessTask task) throws Exception {
        log.info("开始处理文档: {} -> {}, 策略: {}, 是否生效: {}",
                task.fileName, task.indexName, task.splitterType, task.isActive);

        // 读取文件
        File docFile = new File(task.baseDir, task.fileName);
        List<Document> documents = readFile(docFile, task.fileName);

        if (documents.isEmpty()) {
            throw new RuntimeException("文件内容为空或读取失败");
        }

        // 获取分割器
        IDocumentSplitter documentSplitter = documentSplitterManager.getSplitter(task.splitterType);

        // 分割文档
        List<Document> splitDocuments = documentSplitter.split(documents);
        log.info("文档分割完成 - 原始: {}, 分段后: {}", documents.size(), splitDocuments.size());

        // 解析生效日期
        final LocalDateTime validStartDateTime = parseDateTime(task.validStartDate, LocalDateTime.now());
        final LocalDateTime validEndDateTime = parseDateTime(task.validEndDate, LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        final String uploadTime = LocalDateTime.now().format(DATE_FORMATTER);

        // 为每个文档添加元数据
        splitDocuments.forEach(doc -> {
            doc.getMetadata().put("file_name", task.fileName);
            doc.getMetadata().put("is_active", task.isActive);
            doc.getMetadata().put("valid_from_date", validStartDateTime.format(DATE_FORMATTER));
            doc.getMetadata().put("valid_end_date", validEndDateTime.format(DATE_FORMATTER));
            doc.getMetadata().put("upload_time", uploadTime);
        });

        // 获取 VectorStore
        VectorStore vectorStore = openSearchStoreService.createVectorStore(task.indexName);

        // 写入向量库
        vectorStore.add(splitDocuments);
        log.info("成功写入 {} 个向量到索引: {}", splitDocuments.size(), task.indexName);
    }

    /**
     * 读取文件
     */
    private List<Document> readFile(File file, String filename) throws Exception {
        String ext = getFileExtension(filename).toLowerCase();

        org.springframework.core.io.Resource resource = new org.springframework.core.io.FileSystemResource(file);

        if (ext.equals(".pdf")) {
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

        } else if (ext.equals(".txt") || ext.equals(".md") || ext.equals(".docx") || ext.equals(".doc")) {
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> documents = reader.read();
            log.info("读取文件 {}: {}, 文档片段数: {}", ext, filename, documents.size());
            return documents;

        } else {
            throw new RuntimeException("不支持的文件类型: " + ext);
        }
    }

    /**
     * 解析日期时间
     */
    private LocalDateTime parseDateTime(String dateStr, LocalDateTime defaultValue) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return LocalDateTime.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            log.warn("日期格式错误: {}, 使用默认值", dateStr, e);
            return defaultValue;
        }
    }

    /**
     * 获取文件扩展名
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
}
