package com.aiextract.service;

import com.aiextract.model.ReviewMessage;
import com.aiextract.model.ReviewSession;
import com.aiextract.repository.ReviewMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 追问流结束后落两条消息。短事务，失败不影响已展示的回答。
 */
@Service
@RequiredArgsConstructor
public class SalesReviewChatPersistence {

    private final ReviewMessageRepository messageRepository;

    @Transactional(rollbackFor = Exception.class)
    public void save(ReviewSession session, String userContent, String assistantContent) {
        if (assistantContent == null || assistantContent.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        messageRepository.saveAll(List.of(
                ReviewMessage.builder().id(UUID.randomUUID()).sessionId(session.getId())
                        .companyId(session.getCompanyId()).role("user").content(userContent)
                        .createdAt(now).build(),
                ReviewMessage.builder().id(UUID.randomUUID()).sessionId(session.getId())
                        .companyId(session.getCompanyId()).role("assistant").content(assistantContent)
                        // PostgreSQL timestamp 是微秒精度，用 1ms 保证排在 user 之后
                        .createdAt(now.plusNanos(1_000_000)).build()
        ));
    }
}
