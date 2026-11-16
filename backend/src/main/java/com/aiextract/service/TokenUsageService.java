package com.aiextract.service;

import com.aiextract.model.TokenUsageLog;
import com.aiextract.repository.TokenUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Token 用量异步记录服务 — 失败不影响主业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final TokenUsageLogRepository repository;

    /** 旧签名：保留，companyId 从 TokenContext 取 */
    @Async("tokenLogExecutor")
    public void log(UUID userId, String modelType, String modelName, int inputTokens, int outputTokens,
                    int promptChars, int completionChars) {
        log(userId, com.aiextract.config.TokenContext.getCompanyId(), modelType, modelName,
                inputTokens, outputTokens, promptChars, completionChars);
    }

    @Async("tokenLogExecutor")
    public void log(UUID userId, UUID companyId, String modelType, String modelName,
                    int inputTokens, int outputTokens, int promptChars, int completionChars) {
        try {
            repository.save(TokenUsageLog.builder()
                .userId(userId)
                .companyId(companyId)
                .usageDate(LocalDate.now())
                .modelType(modelType)
                .modelName(modelName)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .promptChars(promptChars)
                .completionChars(completionChars)
                .createdAt(LocalDateTime.now())
                .build());
        } catch (Exception e) {
            log.warn("Token 用量记录失败 userId={} companyId={} model={} input={} output={}",
                userId, companyId, modelName, inputTokens, outputTokens, e);
        }
    }
}
