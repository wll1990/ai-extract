package com.aiextract.repository;

import com.aiextract.model.KnowledgeUploadTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 知识批量上传任务数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Repository
public interface KnowledgeUploadTaskRepository extends JpaRepository<KnowledgeUploadTask, UUID> {

    /**
     * 按企业分页查询上传任务（管理端列表 + 前端轮询）。
     */
    Page<KnowledgeUploadTask> findByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);
}
