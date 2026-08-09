package com.aiextract.service;

import com.aiextract.common.TraceContext;
import com.aiextract.config.TokenContext;
import com.aiextract.model.ExperienceGrain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * DashScope 原生 Embedding 服务
 *
 * 通义千问 text-embedding-v4，1024 维。
 * 不走 Spring AI OpenAI 客户端（兼容模式 404），直接调 DashScope API。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeEmbeddingService {

    private static final String URL = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    private static final int EMBEDDING_DIM = 1024;

    private static RestTemplate createRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(15000);
        return new RestTemplate(f);
    }
    private final RestTemplate rest = createRestTemplate();
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TokenUsageService tokenUsageService;

    @Value("${ai.qwen.api-key}")
    private String apiKey;

    /** 单条文本 → 1024 维向量 */
    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("ai.qwen.api-key 未配置，向量服务不可用");
        }
        long t0 = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "text-embedding-v4");
            body.put("input", Map.of("texts", List.of(text)));
            body.put("parameters", Map.of("dimension", 1024));

            ResponseEntity<String> resp = rest.postForEntity(
                URL, new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("DashScope HTTP " + resp.getStatusCode() + ": " + resp.getBody());
            }

            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode embeddings = root.path("output").path("embeddings");
            if (embeddings.isArray() && embeddings.size() > 0) {
                JsonNode vec = embeddings.get(0).path("embedding");
                float[] result = new float[vec.size()];
                { for (int i = 0; i < vec.size(); i++) result[i] = (float) vec.get(i).asDouble(); }

                int totalTokens = root.path("usage").path("total_tokens").asInt(0);
                tokenUsageService.log(TokenContext.get(), "EMBEDDING", "text-embedding-v4", totalTokens, 0, 0, 0);

                log.info("向量模型返回 dim={} {}ms text={}",
                    result.length, System.currentTimeMillis() - t0,
                    text.substring(0, Math.min(50, text.length())));
                return result;
            }
            throw new RuntimeException("DashScope 响应格式异常: " + resp.getBody());
        } catch (Exception e) {
            log.error("DashScope embedding 失败 {}ms", System.currentTimeMillis() - t0, e);
            throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
        }
    }

    /** DashScope API 单次调用最大文本数 */
    private static final int MAX_BATCH_SIZE = 10;
    private static final int MAX_RETRY_PER_CHUNK = 3;

    /** 批量嵌入，内部按 {@value MAX_BATCH_SIZE} 条分片调用，per-chunk 重试 */
    public List<float[]> embedBatch(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("ai.qwen.api-key 未配置，向量服务不可用");
        }
        if (texts.isEmpty()) return List.of();

        long t0 = System.currentTimeMillis();
        List<float[]> allResults = new ArrayList<>();
        List<String> failedChunks = new ArrayList<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(start + MAX_BATCH_SIZE, texts.size());
            List<String> chunk = texts.subList(start, end);
            boolean chunkOk = false;

            for (int retry = 0; retry < MAX_RETRY_PER_CHUNK && !chunkOk; retry++) {
                try {
                    if (retry > 0) {
                        long waitMs = (long) Math.pow(2, retry) * 1000;
                        Thread.sleep(waitMs);
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("model", "text-embedding-v4");
                    body.put("input", Map.of("texts", chunk));
                    body.put("parameters", Map.of("dimension", EMBEDDING_DIM));

                    ResponseEntity<String> resp = rest.postForEntity(
                        URL, new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

                    if (!resp.getStatusCode().is2xxSuccessful()) {
                        throw new RuntimeException("DashScope HTTP " + resp.getStatusCode() + ": " + resp.getBody());
                    }

                    JsonNode root = objectMapper.readTree(resp.getBody());
                    JsonNode embeddings = root.path("output").path("embeddings");
                    if (embeddings.isArray()) {
                        for (JsonNode emb : embeddings) {
                            JsonNode vec = emb.path("embedding");
                            float[] arr = new float[vec.size()];
                            for (int i = 0; i < vec.size(); i++) arr[i] = (float) vec.get(i).asDouble();
                            allResults.add(arr);
                        }
                    }
                    int totalTokens = root.path("usage").path("total_tokens").asInt(0);
                    tokenUsageService.log(TokenContext.get(), "EMBEDDING", "text-embedding-v4", totalTokens, 0, 0, 0);
                    chunkOk = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("批量向量生成被中断", e);
                } catch (Exception e) {
                    if (retry == MAX_RETRY_PER_CHUNK - 1) {
                        log.error("DashScope chunk 重试耗尽 start={} retry={}: {}", start, retry, e.getMessage());
                        failedChunks.add("chunk[" + start + "-" + end + "]");
                    } else {
                        log.warn("DashScope chunk 重试 {}/{} start={}: {}", retry + 1, MAX_RETRY_PER_CHUNK, start, e.getMessage());
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        if (!failedChunks.isEmpty()) {
            log.warn("批量向量部分失败 {}ms totalChunks={} failedChunks={} results={}",
                elapsed, (texts.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE, failedChunks.size(), allResults.size());
        } else {
            log.info("批量向量 dim={} count={} chunks={} {}ms",
                allResults.isEmpty() ? 0 : allResults.get(0).length, allResults.size(),
                (texts.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE, elapsed);
        }
        return allResults;
    }

    /** 批量保存 embedding 到 pgvector — PGvector 驱动直传，二进制编码 */
    public int[] saveEmbeddings(List<ExperienceGrain> grains, List<float[]> vectors) {
        if (grains.size() != vectors.size()) {
            throw new IllegalArgumentException("grains 和 vectors 数量不一致");
        }
        for (int i = 0; i < vectors.size(); i++) {
            if (vectors.get(i).length != EMBEDDING_DIM) {
                throw new IllegalArgumentException(
                    "向量维度异常 grain[" + i + "] dim=" + vectors.get(i).length + " expected=" + EMBEDDING_DIM);
            }
        }
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 0; i < grains.size(); i++) {
            batchArgs.add(new Object[]{
                new PGvector(vectors.get(i)),
                grains.get(i).getId()
            });
        }
        return jdbcTemplate.batchUpdate(
            "UPDATE experience_grain SET embedding = ? WHERE id = ?", batchArgs);
    }

    /** 批量回填 embedding — 文本拼接 + API调用 + 批量写入 */
    public int[] backfillEmbeddings(List<ExperienceGrain> grains) {
        if (grains.isEmpty()) return new int[0];
        List<String> texts = grains.stream().map(this::grainToText).toList();
        List<float[]> vectors = embedBatch(texts);
        return saveEmbeddings(grains, vectors);
    }

    /**
     * 颗粒文本拼接（用于 embedding） — 仅向量化查询面，回答面字段作为 LLM 上下文使用。
     * 场景 + 描述 + 条件 = 用户提问时的匹配目标；
     * 思路 + 话术 + 避坑 = 检索后喂给 LLM 的 payload，不参与向量化。
     */
    public String grainToText(ExperienceGrain g) {
        String text = String.join(" ",
            g.getSceneTag() != null ? g.getSceneTag() : "",
            g.getSceneDescription() != null ? g.getSceneDescription() : "",
            g.getApplicableCondition() != null ? g.getApplicableCondition() : ""
        ).trim();
        return text.isEmpty() ? "未分类" : text;
    }
}
