package com.aiextract.repository;

import com.aiextract.model.CaseUploadTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 案例批量上传任务数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Repository
public interface CaseUploadTaskRepository extends JpaRepository<CaseUploadTask, UUID> {

    /** 按企业分页查询上传任务（管理端列表 + 前端轮询）。 */
    Page<CaseUploadTask> findByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);
}
