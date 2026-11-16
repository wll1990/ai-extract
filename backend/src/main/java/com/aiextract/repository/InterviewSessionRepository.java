package com.aiextract.repository;

import com.aiextract.model.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 访谈会话数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {

    /**
     * 查询指定空间下状态为进行中/暂停/已创建的会话列表
     *
     * @param spaceId 空间ID
     * @param statuses 状态列表
     * @return 符合条件的会话列表
     */
    @Query("SELECT s FROM InterviewSession s WHERE s.spaceId = :spaceId AND s.status IN :statuses")
    List<InterviewSession> findBySpaceIdAndStatusIn(@Param("spaceId") UUID spaceId,
                                                     @Param("statuses") List<String> statuses);

    /**
     * 查询指定空间已完成的会话数量
     *
     * @param spaceId 空间ID
     * @param status 会话状态
     * @return 已完成会话数量
     */
    long countBySpaceIdAndStatus(UUID spaceId, String status);

    /** 后台任务健康检查：最近窗口内萃取失败会话数 */
    long countByExtractionStatusAndCreatedAtAfter(String extractionStatus, java.time.LocalDateTime after);
    /**
     * 根据多个空间ID查询活跃会话（按最后活跃时间降序）
     *
     * @param spaceIds 空间ID列表
     * @param statuses 状态列表
     * @return 活跃会话列表
     */
    @Query("SELECT s FROM InterviewSession s WHERE s.spaceId IN :spaceIds AND s.status IN :statuses "
            + "ORDER BY s.lastActiveAt DESC")
    List<InterviewSession> findBySpaceIdInAndStatusIn(@Param("spaceIds") List<UUID> spaceIds,
                                                       @Param("statuses") List<String> statuses);

    /**
     * 统计多个空间中指定状态的会话数。
     */
    @Query("SELECT COUNT(s) FROM InterviewSession s WHERE s.spaceId IN :spaceIds AND s.status = :status")
    long countBySpaceIdInAndStatus(@Param("spaceIds") List<UUID> spaceIds, @Param("status") String status);

    /**
     * 按访谈类型统计活跃会话数。
     * 用于创建新访谈前检查同类型是否已有进行中的会话。
     */
    @Query("SELECT COUNT(s) FROM InterviewSession s WHERE s.spaceId IN :spaceIds "
         + "AND s.status IN :statuses AND s.interviewType = :interviewType")
    long countBySpaceIdInAndStatusInAndInterviewType(
        @Param("spaceIds") List<UUID> spaceIds,
        @Param("statuses") List<String> statuses,
        @Param("interviewType") String interviewType);

    /**
     * 只更新萃取状态列（不写整实体）。
     *
     * <p>异步萃取管道与「强制完成」并发时，整实体 save 会用旧快照把
     * status=completed 覆盖回 in_progress（丢失更新），因此这里必须走定向 UPDATE。</p>
     */
    @Modifying
    @Query("UPDATE InterviewSession s SET s.extractionStatus = :status, s.extractionError = :error "
         + "WHERE s.id = :id")
    int updateExtractionState(@Param("id") UUID id,
                              @Param("status") String status,
                              @Param("error") String error);

    /** 萃取师删除时解除访谈引用，保留历史会话本身。 */
    @Modifying
    @Query("UPDATE InterviewSession s SET s.expertSkillId = null WHERE s.expertSkillId = :expertId")
    int clearExpertSkillId(@Param("expertId") UUID expertId);

    /** 同上，额外写 extractedAt（仅在萃取完成时使用）。 */
    @Modifying
    @Query("UPDATE InterviewSession s SET s.extractionStatus = :status, s.extractionError = :error, "
         + "s.extractedAt = :extractedAt WHERE s.id = :id")
    int updateExtractionStateWithTime(@Param("id") UUID id,
                                      @Param("status") String status,
                                      @Param("error") String error,
                                      @Param("extractedAt") java.time.LocalDateTime extractedAt);
}

