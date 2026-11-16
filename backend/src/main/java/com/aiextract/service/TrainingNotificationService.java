package com.aiextract.service;

import com.aiextract.config.RolePermissions;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.User;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 培训事件通知 —— 领域方法 + 对通用 {@link NotificationService} 的委托。
 *
 * <p>保留原有 public 方法签名，避免培训业务与既有测试受影响。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Service
@RequiredArgsConstructor
public class TrainingNotificationService {

    private final NotificationService notificationService;
    private final TrainingCohortRepository cohortRepository;
    private final UserRepository userRepository;

    public void notifyUser(UUID userId, UUID companyId, String type, String title,
                           String content, String link, String bizType, UUID bizId,
                           String dedupeKey) {
        notificationService.notifyUser(userId, companyId, type, title, content, link,
                bizType, bizId, dedupeKey);
    }

    public void notifyLearner(TrainingAssignment assignment, String type, String title,
                              String content, String link, String dedupeKey) {
        notificationService.notifyUser(assignment.getLearnerId(), assignment.getCompanyId(),
                type, title, content, link, "training_assignment", assignment.getId(), dedupeKey);
    }

    /** 通知批次复核人：优先导师，未指定导师时通知企业管理员 */
    public void notifyReviewer(TrainingCohort cohort, String type, String title,
                               String content, String link, String dedupeSuffix) {
        if (cohort.getMentorId() != null) {
            notificationService.notifyUser(cohort.getMentorId(), cohort.getCompanyId(),
                    type, title, content, link, "training_cohort", cohort.getId(),
                    dedupeSuffix + ":" + cohort.getMentorId());
            return;
        }
        List<User> admins = userRepository.findByCompanyIdAndRoleInAndIsActiveTrue(
                cohort.getCompanyId(), List.of(RolePermissions.COMPANY_ADMIN,
                        RolePermissions.SUPER_ADMIN));
        for (User admin : admins) {
            notificationService.notifyUser(admin.getId(), cohort.getCompanyId(), type, title,
                    content, link, "training_cohort", cohort.getId(),
                    dedupeSuffix + ":" + admin.getId());
        }
    }

    public void notifyReviewerByCohortId(UUID cohortId, String type, String title,
                                         String content, String link, String dedupeSuffix) {
        cohortRepository.findById(cohortId).ifPresent(cohort ->
                notifyReviewer(cohort, type, title, content, link, dedupeSuffix));
    }

    // ==================== 收件箱（历史调用方兼容） ====================

    public Page<UserNotification> listMine(boolean unreadOnly, int page, int size) {
        return notificationService.listMine(unreadOnly, page, size);
    }

    public long unreadCount() {
        return notificationService.unreadCount();
    }

    public void markRead(UUID id) {
        notificationService.markRead(id);
    }

    public int markAllRead() {
        return notificationService.markAllRead();
    }
}
