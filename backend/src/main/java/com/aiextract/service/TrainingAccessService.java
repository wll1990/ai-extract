package com.aiextract.service;

import com.aiextract.config.Permission;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * 培训模块的访问控制 —— 所有归属断言集中在这里，禁止业务代码裸 {@code findById}。
 *
 * <p><b>三条纪律</b>：</p>
 * <ol>
 *   <li>跨企业一律返回 <b>404</b>，不用 403——403 会泄露「这个 UUID 属于别的企业」；</li>
 *   <li>先查存在性再放行管理员，不复用 {@code assertSkillOwnership} 的「super_admin 前置 return」写法
 *       （那种写法会放过不存在的幽灵 ID）；</li>
 *   <li>「是不是复核人」由 URL 级 {@code TRAINING_REVIEW} 回答，
 *       「能不能复核这个批次」由本类按 {@code cohort.mentor_id} 回答。</li>
 * </ol>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Service
@RequiredArgsConstructor
public class TrainingAccessService {

    private final TrainingCohortRepository cohortRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingTaskRecordRepository taskRecordRepository;
    private final TrainingAttemptRepository attemptRepository;

    /** 当前企业 ID；培训全部数据都要求企业归属 */
    public UUID requireCompanyId() {
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(403, "无企业归属，无权访问培训数据");
        }
        return companyId;
    }

    /** 是否有培训管理权限（company_admin / super_admin） */
    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> Permission.TRAINING_MANAGE.equals(a.getAuthority()));
    }

    /** 读取批次并校验企业归属（不存在与跨企业都返回 404） */
    public TrainingCohort requireCohort(UUID cohortId) {
        TrainingCohort cohort = cohortRepository.findById(cohortId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        if (!requireCompanyId().equals(cohort.getCompanyId())) {
            throw new BusinessException(404, "培训数据不存在或不可访问");
        }
        return cohort;
    }

    /**
     * 校验能否复核/查看该批次。
     *
     * <p>管理员可看本企业全部批次；导师只能看 {@code mentor_id} 指向自己的批次。</p>
     */
    public TrainingCohort assertCanReview(UUID cohortId) {
        TrainingCohort cohort = requireCohort(cohortId);
        if (isAdmin()) {
            return cohort;
        }
        // 显式判空：Objects.equals(null, null) 会返回 true，不能把「都没值」当成导师匹配
        if (TokenContext.get() == null || cohort.getMentorId() == null
                || !Objects.equals(cohort.getMentorId(), TokenContext.get())) {
            // 统一 404：403 会告诉对方「这个批次确实存在，只是你无权」，可被用来枚举
            throw new BusinessException(404, "培训数据不存在或不可访问");
        }
        return cohort;
    }

    /** 学员读取自己的分配；不是本人或跨企业都返回 404 */
    public TrainingAssignment requireLearnerAssignment(UUID assignmentId) {
        TrainingAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        if (!requireCompanyId().equals(assignment.getCompanyId())
                || !Objects.equals(assignment.getLearnerId(), TokenContext.get())) {
            throw new BusinessException(404, "培训数据不存在或不可访问");
        }
        return assignment;
    }

    /** 学员读取自己的任务进度行；不是本人或跨企业都返回 404 */
    public TrainingTaskRecord requireLearnerRecord(UUID recordId) {
        TrainingTaskRecord record = taskRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        assertOwnedByLearner(record.getAssignmentId());
        return record;
    }

    /** 学员读取自己的尝试；不是本人或跨企业都返回 404 */
    public TrainingAttempt requireLearnerAttempt(UUID attemptId) {
        TrainingAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        assertOwnedByLearner(attempt.getAssignmentId());
        return attempt;
    }

    private void assertOwnedByLearner(UUID assignmentId) {
        TrainingAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        if (!requireCompanyId().equals(assignment.getCompanyId())
                || !Objects.equals(assignment.getLearnerId(), TokenContext.get())) {
            throw new BusinessException(404, "培训数据不存在或不可访问");
        }
    }
}
