package com.aiextract.repository;

import com.aiextract.model.SkillMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
public interface SkillMessageRepository extends JpaRepository<SkillMessage, UUID> {
    /**
     * 查询，按，conversation，id，排序，按，created，at，升序。
     * @param conversationId 参数
     * @return 查询结果列表
     */
    List<SkillMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
    /**
     * 统计（Conversation,Id）。
     * @param conversationId conversationId
     * @return 统计数量
     */
    long countByConversationId(UUID conversationId);

    @Query("SELECT m.conversationId, COUNT(m) FROM SkillMessage m WHERE m.conversationId IN :ids GROUP BY m.conversationId")
    /**
     * 统计（Conversation,Id）。
     * @param ids ids
     * @return 结果列表
     */
    List<Object[]> countByConversationIdIn(@Param("ids") List<UUID> ids);
    /**
     * 统计某用户的全部 user 角色消息数（跨会话跨模式），用于 C 端游客免费额度判定。
     * 走 idx_skill_conv_user + idx_skill_msg_conv 两个现有索引。
     */
    @Query("SELECT COUNT(m) FROM SkillMessage m, SkillConversation c " +
           "WHERE m.conversationId = c.id AND c.userId = :userId AND m.role = 'user'")
    /**
     * 统计（User,Messages,User,Id）。
     * @param userId userId
     * @return 统计数量
     */
    long countUserMessagesByUserId(@Param("userId") UUID userId);

    /** 删除会话下所有消息 */
    void deleteByConversationId(UUID conversationId);

}
