package com.aiextract.repository;

import com.aiextract.model.InterviewInviteCode;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
