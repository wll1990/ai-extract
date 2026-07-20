package com.aiextract.repository;

import com.aiextract.model.SkillEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
public interface SkillEvaluationRepository extends JpaRepository<SkillEvaluation, UUID> {
    /**
     * 查询，按，skill，id，排序，按，created，at，降序。
     * @param skillId 参数
     * @return 查询结果列表
     */
    List<SkillEvaluation> findBySkillIdOrderByCreatedAtDesc(UUID skillId);
    /**
     * 查询（Conversation,Id）。
     * @param conversationId conversationId
     * @return 结果列表
     */
    List<SkillEvaluation> findByConversationId(UUID conversationId);


}
