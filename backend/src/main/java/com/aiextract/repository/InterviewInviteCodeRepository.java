package com.aiextract.repository;

import com.aiextract.model.InterviewInviteCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 访谈邀请码数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-07-24
 */
@Repository
public interface InterviewInviteCodeRepository extends JpaRepository<InterviewInviteCode, UUID> {

    Optional<InterviewInviteCode> findByCode(String code);

    /** 原子占用一次：max_uses=0/null 表示不限；超限返回 0，避免并发绕过 */
    @Modifying
    @Query(value = "UPDATE interview_invite_code SET used_count = COALESCE(used_count, 0) + 1 "
            + "WHERE id = :id AND (max_uses IS NULL OR max_uses = 0 "
            + "OR COALESCE(used_count, 0) < max_uses)", nativeQuery = true)
    int consume(@Param("id") UUID id);
}
