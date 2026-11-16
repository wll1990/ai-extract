package com.aiextract.repository;

import com.aiextract.model.DemoRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface DemoRequestRepository extends JpaRepository<DemoRequest, UUID> {

    Page<DemoRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 运营按跟进状态筛选。 */
    Page<DemoRequest> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    /** 同一联系方式短时间重复提交时复用原记录，避免刷表。 */
    Optional<DemoRequest> findFirstByContactAndCreatedAtAfterOrderByCreatedAtDesc(
            String contact, LocalDateTime after);
}
