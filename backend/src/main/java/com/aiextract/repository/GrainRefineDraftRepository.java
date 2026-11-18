package com.aiextract.repository;

import com.aiextract.model.GrainRefineDraft;
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
 * 颗粒纠偏草稿仓储。
 *
 * @author AI Extract Team
 * @since 2026-11-18
 */
@Repository
public interface GrainRefineDraftRepository extends JpaRepository<GrainRefineDraft, UUID> {

    /** 同一用户对同一颗粒的进行中草稿（多轮对话续接） */
    Optional<GrainRefineDraft> findFirstByGrainIdAndUserIdAndStatusOrderByCreatedAtDesc(
            UUID grainId, UUID userId, String status);

    /** 最新一条已应用的草稿 —— 回滚只允许回滚它 */
    Optional<GrainRefineDraft> findFirstByGrainIdAndStatusOrderByAppliedAtDesc(
            UUID grainId, String status);

    /** 颗粒最近的纠偏记录（审核面板展示改了什么） */
    List<GrainRefineDraft> findByGrainIdOrderByCreatedAtDesc(UUID grainId, Pageable pageable);

    /** 只统计当前处于 applied 的颗粒数；revert 后自然减少 */
    @Query("SELECT COUNT(DISTINCT d.grainId) FROM GrainRefineDraft d "
            + "WHERE d.skillId = :skillId AND d.status = :status")
    long countDistinctGrainsBySkillIdAndStatus(@Param("skillId") UUID skillId,
                                               @Param("status") String status);

    /** 异步向量重建回写状态，事务只包这一条 UPDATE */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE GrainRefineDraft d SET d.embeddingStatus = :status WHERE d.id = :id")
    int updateEmbeddingStatus(@Param("id") UUID id, @Param("status") String status);

    @Modifying
    @Query("DELETE FROM GrainRefineDraft d WHERE d.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
