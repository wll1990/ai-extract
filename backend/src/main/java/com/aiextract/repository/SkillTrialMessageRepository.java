package com.aiextract.repository;

import com.aiextract.model.SkillTrialMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 试问消息仓储。
 *
 * @author AI Extract Team
 * @since 2026-11-18
 */
@Repository
public interface SkillTrialMessageRepository extends JpaRepository<SkillTrialMessage, UUID> {

    List<SkillTrialMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /** 组装多轮上下文：取最近 N 条（倒序 + Pageable），调用方再反转 */
    List<SkillTrialMessage> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);

    /** 幂等：同一会话内重复的 clientMessageId 不重复落库 */
    Optional<SkillTrialMessage> findFirstBySessionIdAndClientMessageIdAndRole(
            UUID sessionId, String clientMessageId, String role);

    long countBySessionId(UUID sessionId);

    /** 90 天清理（会话删除时本表由 FK CASCADE 处理，这里兜底按时间清） */
    @Modifying
    @Query("DELETE FROM SkillTrialMessage m WHERE m.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
