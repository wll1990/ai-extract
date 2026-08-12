package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.model.ExperienceGrain;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import com.pgvector.PGvector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * DashScope Embedding 服务 — 使用官方 SDK。
 *
 * <p>模型 text-embedding-v4，1024 维。SDK 自动处理 endpoint 和协议。</p>
 */
@Slf4j
@Service
public class DashScopeEmbeddingService {

    @Value("${ai.dashscope.base-url}")
    private String baseUrl;

    @Value("${ai.dashscope.embedding.model}")
    private String model;

    @Value("${ai.dashscope.embedding.dimension}")
    private int dimension;

    private final JdbcTemplate jdbcTemplate;
    private final TokenUsageService tokenUsageService;

    public DashScopeEmbeddingService(JdbcTemplate jdbcTemplate, TokenUsageService tokenUsageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenUsageService = tokenUsageService;
    }

    /** SDK 自动读 DASHSCOPE_API_KEY，只需设 workspace URL */
    @Value("${ai.dashscope.base-url}")
    public void setBaseHttpUrl(String url) {
        Constants.baseHttpApiUrl = url + "/api/v1";
    }

    /** 单条文本 → 向量 */
    public float[] embed(String text) {
        long t0 = System.currentTimeMillis();
        try {
            TextEmbeddingParam param = TextEmbeddingParam.builder()
                    .model(model)
                    .texts(Collections.singletonList(text))
                    .dimension(dimension)
                    .build();
            TextEmbeddingResult result = new TextEmbedding().call(param);

            List<Double> vec = result.getOutput().getEmbeddings().get(0).getEmbedding();
            float[] arr = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i).floatValue();

            int totalTokens = result.getUsage().getTotalTokens();
            tokenUsageService.log(TokenContext.get(), "EMBEDDING", model, totalTokens, 0, 0, 0);

            log.info("向量 SDK dim={} {}ms text={}", arr.length,
                    System.currentTimeMillis() - t0, text.substring(0, Math.min(50, text.length())));
            return arr;
        } catch (NoApiKeyException e) {
            throw new RuntimeException("DASHSCOPE_API_KEY 未配置，向量服务不可用", e);
        } catch (ApiException e) {
            log.error("SDK embedding 失败 {}ms status={} msg={}",
                    System.currentTimeMillis() - t0, e.getStatus(), e.getMessage());
            throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
        }
    }

    /** 批量嵌入，内部按 {@value MAX_BATCH_SIZE} 条分片调用，per-chunk 重试 */
    private static final int MAX_BATCH_SIZE = 10;
    private static final int MAX_RETRY_PER_CHUNK = 3;

    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        long t0 = System.currentTimeMillis();
        List<float[]> allResults = new ArrayList<>();
        List<String> failedChunks = new ArrayList<>();

        for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(start + MAX_BATCH_SIZE, texts.size());
            List<String> chunk = texts.subList(start, end);
            boolean chunkOk = false;

            for (int retry = 0; retry < MAX_RETRY_PER_CHUNK && !chunkOk; retry++) {
                try {
                    if (retry > 0) Thread.sleep((long) Math.pow(2, retry) * 1000);

                    TextEmbeddingParam param = TextEmbeddingParam.builder()
                            .model(model).texts(chunk).dimension(dimension).build();
                    TextEmbeddingResult result = new TextEmbedding().call(param);

                    for (var emb : result.getOutput().getEmbeddings()) {
                        List<Double> vec = emb.getEmbedding();
                        float[] arr = new float[vec.size()];
                        for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i).floatValue();
                        allResults.add(arr);
                    }
                    int totalTokens = result.getUsage().getTotalTokens();
                    tokenUsageService.log(TokenContext.get(), "EMBEDDING", model, totalTokens, 0, 0, 0);
                    chunkOk = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("批量向量生成被中断", e);
                } catch (Exception e) {
                    if (retry == MAX_RETRY_PER_CHUNK - 1) {
                        log.error("SDK chunk 重试耗尽 start={}: {}", start, e.getMessage());
                        failedChunks.add("chunk[" + start + "-" + end + "]");
                    } else {
                        log.warn("SDK chunk 重试 {}/{} start={}: {}", retry + 1, MAX_RETRY_PER_CHUNK, start, e.getMessage());
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        if (!failedChunks.isEmpty()) {
            log.warn("批量向量部分失败 {}ms failedChunks={} results={}", elapsed, failedChunks.size(), allResults.size());
        } else {
            log.info("批量向量 SDK dim={} count={} chunks={} {}ms",
                    allResults.isEmpty() ? 0 : allResults.get(0).length, allResults.size(),
                    (texts.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE, elapsed);
        }
        return allResults;
    }

    /** 批量保存 embedding 到 pgvector */
    public int[] saveEmbeddings(List<ExperienceGrain> grains, List<float[]> vectors) {
        if (grains.size() != vectors.size())
            throw new IllegalArgumentException("grains 和 vectors 数量不一致");
        for (int i = 0; i < vectors.size(); i++) {
            if (vectors.get(i).length != dimension)
                throw new IllegalArgumentException(
                        "向量维度异常 grain[" + i + "] dim=" + vectors.get(i).length + " expected=" + dimension);
        }
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 0; i < grains.size(); i++)
            batchArgs.add(new Object[]{new PGvector(vectors.get(i)), grains.get(i).getId()});
        return jdbcTemplate.batchUpdate("UPDATE experience_grain SET embedding = ? WHERE id = ?", batchArgs);
    }

    /** 批量回填 embedding */
    public int[] backfillEmbeddings(List<ExperienceGrain> grains) {
        if (grains.isEmpty()) return new int[0];
        return saveEmbeddings(grains, embedBatch(grains.stream().map(this::grainToText).toList()));
    }

    /** 颗粒文本拼接 */
    public String grainToText(ExperienceGrain g) {
        String text = String.join(" ",
                g.getSceneTag() != null ? g.getSceneTag() : "",
                g.getSceneDescription() != null ? g.getSceneDescription() : "",
                g.getApplicableCondition() != null ? g.getApplicableCondition() : "").trim();
        return text.isEmpty() ? "未分类" : text;
    }
}
