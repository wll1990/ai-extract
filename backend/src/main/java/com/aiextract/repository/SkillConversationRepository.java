package com.aiextract.repository;

import com.aiextract.model.SkillConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
public interface SkillConversationRepository extends JpaRepository<SkillConversation, UUID> {
    /**
     * 查询，按，skill，id，和，user，id，排序，按，updated，at，降序。
     * @param skillId 参数
     * @param userId 参数
     * @return 查询结果列表
     */
    List<SkillConversation> findBySkillIdAndUserIdOrderByUpdatedAtDesc(UUID skillId, UUID userId);
    /**
     * 查询（Skill,Id,Updated,At）。
     * @param skillId skillId
     * @return 分页结果
     */
    Page<SkillConversation> findBySkillIdOrderByUpdatedAtDesc(UUID skillId, Pageable pageable);
    /**
     * 查询（Updated,At）。
     * @return 分页结果
     */
    Page<SkillConversation> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    /**
     * 按 skill + user + mode 查询会话列表，按更新时间降序。
     */
    List<SkillConversation> findBySkillIdAndUserIdAndModeOrderByUpdatedAtDesc(
        UUID skillId, UUID userId, String mode);

    /**
     * 按技能 ID 列表分页查询，按更新时间降序（企业数据范围过滤）。
     */
    Page<SkillConversation> findBySkillIdInOrderByUpdatedAtDesc(List<UUID> skillIds, Pageable pageable);

}
