package com.aiextract.service;

import com.aiextract.dto.ReviewMessageView;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ChatChunk;
import com.aiextract.model.ReviewMessage;
import com.aiextract.model.ReviewSession;
import com.aiextract.repository.ReviewMessageRepository;
import com.aiextract.repository.ReviewReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 复盘追问：基于报告 + 对话原文回答，SSE 流式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesReviewChatService {

    private final SalesAccessService access;
    private final ReviewReportRepository reportRepository;
    private final ReviewMessageRepository messageRepository;
    private final com.aiextract.repository.ReviewCoachNoteRepository noteRepository;
    private final SalesReviewPromptBuilder promptBuilder;
    private final SalesReviewChatPersistence persistence;
    private final SalesReviewRateLimiter rateLimiter;
    private final ChatStreamAdapter chatStreamAdapter;
    private final QueryGate queryGate;

    public List<ReviewMessageView> history(UUID sessionId) {
        access.requireSession(sessionId);
        // 页面展示完整历史（最多 40 条额度），LLM 上下文仍只取最近 10 条
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(m -> new ReviewMessageView(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();
    }

    public Flux<ChatChunk> stream(UUID sessionId, String content) {
        String blocked = queryGate.auditSimple(content);
        if (blocked != null) {
            return Flux.just(ChatChunk.error(blocked), ChatChunk.done());
        }
        ReviewSession session = access.requireSession(sessionId);
        long persisted = messageRepository.countBySessionIdAndRole(sessionId, "user");
        if (!rateLimiter.tryConsume(sessionId, persisted)) {
            throw new BusinessException(429, "SALES_REVIEW_CHAT_LIMIT", "本次复盘追问次数已达上限");
        }
        String coachNotes = noteRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(n -> "- " + n.getContent())
                .collect(java.util.stream.Collectors.joining("\n"));
        String system = promptBuilder.buildChatSystem(session,
                reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId).orElse(null),
                coachNotes);
        List<Map<String, String>> messages = promptBuilder.buildChatMessages(system, sessionId, content);

        StringBuilder answer = new StringBuilder();
        return chatStreamAdapter.chatStream(messages, Map.of())
                .map(ChatChunk::fromEventMap)
                .doOnNext(chunk -> {
                    if ("content".equals(chunk.getType()) && chunk.getContent() != null) {
                        answer.append(chunk.getContent());
                    }
                })
                .doOnComplete(() -> persistence.save(session, content, answer.toString()))
                .doOnError(e -> log.warn("复盘追问流式失败 sessionId={}", sessionId, e));
    }
}
