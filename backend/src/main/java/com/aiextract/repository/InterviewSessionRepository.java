package com.aiextract.repository;

import com.aiextract.model.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * 按访谈类型统计活跃会话数。
     * 用于创建新访谈前检查同类型是否已有进行中的会话。
     */
    @Query("SELECT COUNT(s) FROM InterviewSession s WHERE s.spaceId IN :spaceIds "
         + "AND s.status IN :statuses AND s.interviewType = :interviewType")
    long countBySpaceIdInAndStatusInAndInterviewType(
        @Param("spaceIds") List<UUID> spaceIds,
        @Param("statuses") List<String> statuses,
        @Param("interviewType") String interviewType);
}

