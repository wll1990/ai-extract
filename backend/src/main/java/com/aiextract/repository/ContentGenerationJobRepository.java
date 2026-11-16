package com.aiextract.repository;

import com.aiextract.model.ContentGenerationJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContentGenerationJobRepository extends JpaRepository<ContentGenerationJob, UUID> {

    Page<ContentGenerationJob> findByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    Optional<ContentGenerationJob> findFirstByCompanyIdAndKindAndDedupeKeyOrderByCreatedAtDesc(
            UUID companyId, String kind, String dedupeKey);

    Optional<ContentGenerationJob> findByIdAndCompanyId(UUID id, UUID companyId);

    /** 超时未结束的任务（进程崩溃/重启遗留），新建任务前回收 */
    List<ContentGenerationJob> findByCompanyIdAndStatusAndCreatedAtBefore(
            UUID companyId, String status, java.time.LocalDateTime before);

    /** 审核台批量展示生成任务，避免 N+1 */
    List<ContentGenerationJob> findByCompanyIdAndIdIn(UUID companyId, Collection<UUID> ids);

    /** 采纳率统计：按任务取草稿数（由业务表 generation_job_id 反查） */
    List<ContentGenerationJob> findByCompanyIdAndCreatedAtAfterOrderByCreatedAtDesc(
            UUID companyId, java.time.LocalDateTime after);

    List<ContentGenerationJob> findByCompanyIdAndKindInAndCreatedAtAfter(
            UUID companyId, Collection<String> kinds, java.time.LocalDateTime after);
}
