package com.aiextract.repository;

import com.aiextract.model.InterviewMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 访谈消息数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface InterviewMessageRepository extends JpaRepository<InterviewMessage, UUID> {

    /**
     * 根据会话ID查询最近N条消息（按创建时间升序）
     *
     * @param sessionId 会话ID
     * @param limit 最大条数
     * @return 消息列表
     */
    @Query(value = "SELECT * FROM ("
            + "SELECT * FROM interview_message WHERE session_id = :sessionId "
            + "ORDER BY created_at DESC LIMIT :limit"
            + ") sub ORDER BY created_at ASC",
            nativeQuery = true)
    List<InterviewMessage> findRecentBySessionId(@Param("sessionId") UUID sessionId,
                                                  @Param("limit") int limit);

    /**
     * 根据会话ID查询所有消息（按创建时间升序）
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    List<InterviewMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
