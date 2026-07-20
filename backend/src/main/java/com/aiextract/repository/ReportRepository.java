package com.aiextract.repository;

import com.aiextract.model.Report;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 报告数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    /**
     * 按空间ID分页查询报告（按创建时间降序）
     *
     * @param spaceId  空间ID
     * @param pageable 分页参数
     * @return 报告分页
     */
    Page<Report> findBySpaceIdOrderByCreatedAtDesc(UUID spaceId, Pageable pageable);

    /**
     * 查询所有报告（分页，按创建时间降序）
     *
     * @param pageable 分页参数
     * @return 报告分页
     */
    Page<Report> findAllByOrderByCreatedAtDesc(Pageable pageable);
    /**
     * 查询（Title,Containing,Ignore,Case,Subtitle,Containing,Ignore,Case,Created,At）。
     * @param titleKeyword titleKeyword
     * @param subtitleKeyword subtitleKeyword
     * @return 分页结果
     */
    Page<Report> findByTitleContainingIgnoreCaseOrSubtitleContainingIgnoreCaseOrderByCreatedAtDesc(
            String titleKeyword, String subtitleKeyword, Pageable pageable);
    /**
     * 统计（Space,Id）。
     * @param spaceIds spaceIds
     * @return 结果列表
     */
    @Query("SELECT r.spaceId, COUNT(r) FROM Report r WHERE r.spaceId IN :spaceIds GROUP BY r.spaceId")
    List<Object[]> countBySpaceIdIn(@Param("spaceIds") List<UUID> spaceIds);
    /** 关键词搜索（标题+副标题，content_json 是 JSONB 不做 LIKE 全扫） */
    @Query("SELECT r FROM Report r WHERE "
            + "LOWER(r.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(r.subtitle) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "ORDER BY r.createdAt DESC")
    /**
     * 搜索（Full,Text）。
     * @param keyword keyword
     * @return 分页结果
     */
    Page<Report> searchFullText(@Param("keyword") String keyword, Pageable pageable);


}
