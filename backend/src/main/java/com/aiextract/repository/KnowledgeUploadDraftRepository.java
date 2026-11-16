package com.aiextract.repository;

import com.aiextract.model.KnowledgeUploadDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface KnowledgeUploadDraftRepository extends JpaRepository<KnowledgeUploadDraft, UUID> {

    List<KnowledgeUploadDraft> findByTaskIdOrderBySectionNoAscCreatedAtAsc(UUID taskId);

    List<KnowledgeUploadDraft> findByTaskIdAndStatus(UUID taskId, String status);

    long countByTaskIdAndStatus(UUID taskId, String status);

    /** 同一任务内标题查重（整本书同一观点常被多章节重复命中） */
    @Query("SELECT COUNT(d) > 0 FROM KnowledgeUploadDraft d " +
            "WHERE d.taskId = :taskId AND LOWER(d.title) = :lowerTitle")
    boolean existsByTaskIdAndLowerTitle(@Param("taskId") UUID taskId,
                                        @Param("lowerTitle") String lowerTitle);
}
