package com.aiextract.repository;

import com.aiextract.model.CompanyRegisterCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRegisterCodeRepository extends JpaRepository<CompanyRegisterCode, UUID> {

    Optional<CompanyRegisterCode> findByCode(String code);

    List<CompanyRegisterCode> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);

    /**
     * 原子消费一次注册码。
     *
     * <p>用单条 UPDATE 同时完成「校验可用性 + used_count 自增 + 记录使用者」，
     * 避免并发注册时两个请求都通过 read-modify-write 校验导致超发
     * （一次性企业管理员码被用两次）。返回 0 表示码已失效/过期/用尽。</p>
     *
     * @return 受影响行数，1 表示消费成功
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CompanyRegisterCode c set c.usedCount = c.usedCount + 1, "
         + "c.lastUsedBy = :userId, c.lastUsedAt = :now "
         + "where c.id = :id and c.enabled = true "
         + "and (c.maxUses is null or c.maxUses = 0 or c.usedCount < c.maxUses) "
         + "and (c.expiresAt is null or c.expiresAt > :now)")
    int consume(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
