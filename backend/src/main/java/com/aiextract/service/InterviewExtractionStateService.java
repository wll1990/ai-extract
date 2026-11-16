package com.aiextract.service;

import com.aiextract.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 访谈萃取状态短事务 — 异步管道（无事务）通过本服务落状态，
 * 避免整段 AI 管道持有数据库连接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewExtractionStateService {

    public static final String PENDING = "pending";
    public static final String EXTRACTING = "extracting";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    private final InterviewSessionRepository sessionRepository;

    @Transactional(rollbackFor = Exception.class)
    public void markExtracting(UUID sessionId) {
        update(sessionId, EXTRACTING, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markDone(UUID sessionId) {
        update(sessionId, DONE, null, LocalDateTime.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFailed(UUID sessionId, String error) {
        String msg = error == null ? "萃取失败" : (error.length() > 500 ? error.substring(0, 500) : error);
        update(sessionId, FAILED, msg, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markPending(UUID sessionId) {
        update(sessionId, PENDING, null, null);
    }

    /**
     * 定向更新萃取状态列。
     *
     * <p>不能 load 整实体再 save：异步管道与 force-complete 并发时，
     * 旧快照会把 status=completed 覆盖回 in_progress（丢失更新）。</p>
     */
    private void update(UUID sessionId, String status, String error, LocalDateTime extractedAt) {
        int rows = extractedAt == null
                ? sessionRepository.updateExtractionState(sessionId, status, error)
                : sessionRepository.updateExtractionStateWithTime(sessionId, status, error, extractedAt);
        if (rows > 0) {
            log.debug("访谈萃取状态更新 sessionId={} status={}", sessionId, status);
        }
    }
}
