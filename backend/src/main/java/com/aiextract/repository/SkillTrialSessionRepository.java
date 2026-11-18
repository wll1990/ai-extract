package com.aiextract.repository;

import com.aiextract.model.SkillTrialSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 试问会话仓储。
 *
 * @author AI Extract Team
 * @since 2026-11-18
 */
@Repository
public interface SkillTrialSessionRepository extends JpaRepository<SkillTrialSession, UUID> {

    /** 属主视角：只看自己的试问会话 */
    Page<SkillTrialSession> findBySkillIdAndUserIdOrderByUpdatedAtDesc(
            UUID skillId, UUID userId, Pageable pageable);

    /** 管理员视角：看本企业该分身的全部试问会话 */
    Page<SkillTrialSession> findBySkillIdOrderByUpdatedAtDesc(UUID skillId, Pageable pageable);

    Optional<SkillTrialSession> findByIdAndSkillId(UUID id, UUID skillId);

    long countBySkillId(UUID skillId);

    /** 审核面板「最近试问时间」 */
    @Query("SELECT MAX(s.updatedAt) FROM SkillTrialSession s WHERE s.skillId = :skillId")
    LocalDateTime findLastTrialAt(@Param("skillId") UUID skillId);

    /** 90 天清理 */
    @Modifying
    @Query("DELETE FROM SkillTrialSession s WHERE s.updatedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
