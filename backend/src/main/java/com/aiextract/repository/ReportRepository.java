package com.aiextract.repository;

import com.aiextract.model.Report;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 报告数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    Page<Report> findBySpaceIdOrderByCreatedAtDesc(UUID spaceId, Pageable pageable);

    Page<Report> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Report> findByTitleContainingIgnoreCaseOrSubtitleContainingIgnoreCaseOrderByCreatedAtDesc(
            String titleKeyword, String subtitleKeyword, Pageable pageable);

    @Query("SELECT r.spaceId, COUNT(r) FROM Report r WHERE r.spaceId IN :spaceIds GROUP BY r.spaceId")
    List<Object[]> countBySpaceIdIn(@Param("spaceIds") List<UUID> spaceIds);

    @Query("SELECT r FROM Report r WHERE "
            + "LOWER(r.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(r.subtitle) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "ORDER BY r.createdAt DESC")
    Page<Report> searchFullText(@Param("keyword") String keyword, Pageable pageable);

    Optional<Report> findBySessionId(UUID sessionId);
}
