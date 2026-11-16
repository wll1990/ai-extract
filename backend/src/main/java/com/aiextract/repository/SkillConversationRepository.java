package com.aiextract.repository;

import com.aiextract.model.SkillConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** 只返回与本企业用户产生的会话，避免跨企业用户对话出现在企业后台 */
    @Query("SELECT c FROM SkillConversation c, User u WHERE c.userId = u.id "
            + "AND c.skillId = :skillId AND u.companyId = :companyId ORDER BY c.updatedAt DESC")
    Page<SkillConversation> findCompanyConversationsBySkill(
            @Param("skillId") UUID skillId, @Param("companyId") UUID companyId, Pageable pageable);

    @Query("SELECT c FROM SkillConversation c, User u WHERE c.userId = u.id "
            + "AND c.skillId IN :skillIds AND u.companyId = :companyId ORDER BY c.updatedAt DESC")
    Page<SkillConversation> findCompanyConversationsBySkillIn(
            @Param("skillIds") List<UUID> skillIds, @Param("companyId") UUID companyId, Pageable pageable);

    /** 按会话 ID + 参与者企业查询：跨企业会话在 SQL 层就查不到 */
    @Query("SELECT c FROM SkillConversation c, User u WHERE c.id = :id "
            + "AND c.userId = u.id AND u.companyId = :companyId")
    java.util.Optional<SkillConversation> findByIdAndParticipantCompany(
            @Param("id") UUID id, @Param("companyId") UUID companyId);

}
