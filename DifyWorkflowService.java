package com.ws16289.difydemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws16289.difydemo.config.DifyConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Dify 工作流服务
 * 提供 Dify Workflow 的调用功能
 */
@Slf4j
@Service
public class DifyWorkflowService {

    @Autowired
    private DifyConfig difyConfig;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;

    public DifyWorkflowService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * 调用 Dify 工作流
     *
     * @param inputs 工作流输入参数
     * @return 工作流执行结果
     */
    public JsonNode callWorkflow(String inputs) {
        String url = difyConfig.getBaseUrl() + "/workflows/run";

        try {
            // 构建请求体
            String requestBody = buildRequestBody(inputs);

            // 创建请求
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + difyConfig.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            log.info("调用 Dify Workflow: {}", url);
            log.info("请求参数: {}", requestBody);

            // 执行请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No response body";
                    log.error("Dify Workflow 调用失败: {}, 响应: {}", response.code(), errorBody);
                    throw new RuntimeException("Dify Workflow 调用失败: " + response.code() + ", 错误: " + errorBody);
                }

                String responseBody = response.body().string();
                log.info("Dify Workflow 响应: {}", responseBody);

                return objectMapper.readTree(responseBody);
            }
        } catch (IOException e) {
            log.error("调用 Dify Workflow 时发生异常", e);
            throw new RuntimeException("调用 Dify Workflow 失败", e);
        }
    }

    /**
     * 流式调用 Dify 工作流 (SSE)
     *
     * @param inputs 工作流输入参数
     * @param emitter SSE 发射器
     */
    public void streamWorkflow(String inputs, SseEmitter emitter) {
        String url = difyConfig.getBaseUrl() + "/workflows/run";

        executorService.submit(() -> {
            try {
                String requestBody = buildStreamRequestBody(inputs);

                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + difyConfig.getApiKey())
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                        .build();

                log.info("流式调用 Dify Workflow: {}", url);

                try (Response response = httpClient.newCall(request).execute()) {// 发送同步 HTTP 请求
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "No response body";
                        log.error("Dify Workflow 调用失败: {}, 响应: {}", response.code(), errorBody);
                        emitter.completeWithError(new RuntimeException("调用失败: " + errorBody));
                        return;
                    }

                    // 读取 SSE 响应
                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        emitter.completeWithError(new RuntimeException("响应体为空"));
                        return;
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8));
                    String line;

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {//SSE 格式：每行以 data: 开头
                            String eventData = line.substring(6);
                            try {
                                JsonNode dataNode = objectMapper.readTree(eventData);//objectMapper.readTree() - 解析 JSON
                                emitter.send(SseEmitter.event().data(dataNode));

                                // 检查是否结束
                                JsonNode event = dataNode.path("event");
                                if (event.asText().equals("workflow_finished") ||
                                    event.asText().equals("workflow_failed")) {
                                    emitter.complete();
                                    break;
                                }
                            } catch (Exception e) {
                                log.error("解析 SSE 数据失败: {}", eventData, e);
                            }
                        }
                    }
                    emitter.complete();
                }
            } catch (Exception e) {
                log.error("流式调用 Dify Workflow 失败", e);
                emitter.completeWithError(e);
            }
        });
    }

    /**
     * 流式调用指定的 Workflow (SSE)
     *
     * @param workflowId 工作流 ID
     * @param inputs 输入参数
     * @param emitter SSE 发射器
     */
    public void streamWorkflowById(String workflowId, String inputs, SseEmitter emitter) {
        String url = difyConfig.getBaseUrl() + "/workflows/" + workflowId + "/run";

        executorService.submit(() -> {
            try {
                String requestBody = buildStreamRequestBody(inputs);

                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + difyConfig.getApiKey())
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                        .build();

                log.info("流式调用 Dify Workflow: {}, Workflow ID: {}", url, workflowId);

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "No response body";
                        log.error("Dify Workflow 调用失败: {}, 响应: {}", response.code(), errorBody);
                        emitter.completeWithError(new RuntimeException("调用失败: " + errorBody));
                        return;
                    }

                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        emitter.completeWithError(new RuntimeException("响应体为空"));
                        return;
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8));
                    String line;

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String eventData = line.substring(6);
                            try {
                                JsonNode dataNode = objectMapper.readTree(eventData);
                                emitter.send(SseEmitter.event().data(dataNode));

                                JsonNode event = dataNode.path("event");
                                if (event.asText().equals("workflow_finished") ||
                                    event.asText().equals("workflow_failed")) {
                                    emitter.complete();
                                    break;
                                }
                            } catch (Exception e) {
                                log.error("解析 SSE 数据失败: {}", eventData, e);
                            }
                        }
                    }
                    emitter.complete();
                }
            } catch (Exception e) {
                log.error("流式调用 Dify Workflow 失败", e);
                emitter.completeWithError(e);
            }
        });
    }

    /**
     * 构建请求体
     *
     * @param inputs 输入参数
     * @return JSON 字符串
     */
    private String buildRequestBody(String inputs) {
        try {
            String json = String.format("""
                {
                    "inputs": %s,
                    "response_mode": "blocking",
                    "user": "user-001"
                }
                """, inputs);
            return json;
        } catch (Exception e) {
            throw new RuntimeException("构建请求体失败", e);
        }
    }

    /**
     * 构建流式请求体
     *
     * @param inputs 输入参数
     * @return JSON 字符串
     */
    private String buildStreamRequestBody(String inputs) {
        try {
            String json = String.format("""
                {
                    "inputs": %s,
                    "response_mode": "streaming",
                    "user": "user-001"
                }
                """, inputs);
            return json;
        } catch (Exception e) {
            throw new RuntimeException("构建请求体失败", e);
        }
    }

    /**
     * 调用指定的 Workflow
     *
     * @param workflowId 工作流 ID
     * @param inputs 输入参数
     * @return 工作流执行结果
     */
    public JsonNode callWorkflowById(String workflowId, String inputs) {
        String url = difyConfig.getBaseUrl() + "/workflows/" + workflowId + "/run";

        try {
            String requestBody = buildRequestBody(inputs);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + difyConfig.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            log.info("调用 Dify Workflow: {}, Workflow ID: {}", url, workflowId);

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No response body";
                    log.error("Dify Workflow 调用失败: {}, 响应: {}", response.code(), errorBody);
                    throw new RuntimeException("Dify Workflow 调用失败: " + response.code() + ", 错误: " + errorBody);
                }

                String responseBody = response.body().string();
                return objectMapper.readTree(responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("调用 Dify Workflow 失败", e);
        }
    }
}
