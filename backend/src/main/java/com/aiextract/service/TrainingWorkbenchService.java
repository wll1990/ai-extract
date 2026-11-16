package com.aiextract.service;

import com.aiextract.config.Permission;
import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingCertificationRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 导师工作台（TR-07）：待复核、我带的批次进度、逾期学员。
 *
 * <p>数据范围：有 TRAINING_MANAGE 的管理员看本企业全部批次；
 * 导师只看 mentorId = 自己 的批次。列表全部批量查询，避免逐批次/逐人 N+1。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingWorkbenchService {

    private static final int MAX_PENDING = 100;
    private static final int MAX_OVERDUE = 100;

    private final TrainingCohortRepository cohortRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingAttemptRepository attemptRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingCertificationRepository certificationRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> workbench() {
        UUID userId = TokenContext.get();
        UUID companyId = TokenContext.getCompanyId();
        if (userId == null || companyId == null) {
            throw new BusinessException(403, "缺少企业信息");
        }
        String role = userRepository.findById(userId).map(u -> u.getRole()).orElse("");
        boolean manageAll = RolePermissions.hasPermission(role, Permission.TRAINING_MANAGE);

        List<TrainingCohort> cohorts = manageAll
                ? cohortRepository.findByCompanyIdOrderByCreatedAtDesc(companyId)
                : cohortRepository.findByCompanyIdAndMentorIdOrderByCreatedAtDesc(
                        companyId, userId, org.springframework.data.domain.PageRequest.of(0, 200))
                        .getContent();
        if (cohorts.isEmpty()) {
            return Map.of("pendingReviews", List.of(), "pendingCertifications", List.of(),
                    "cohorts", List.of(), "overdueLearners", List.of(),
                    "counts", Map.of("pendingReview", 0, "pendingCertification", 0, "overdue", 0));
        }

        Map<UUID, String> cohortNames = cohorts.stream()
                .collect(Collectors.toMap(TrainingCohort::getId,
                        c -> c.getName() != null ? c.getName() : "培训班", (a, b) -> a));
        List<UUID> cohortIds = new ArrayList<>(cohortNames.keySet());
        List<TrainingAssignment> assignments = assignmentRepository
                .findByCohortIdInOrderByCreatedAtAsc(cohortIds);
        Map<UUID, TrainingAssignment> assignmentById = assignments.stream()
                .collect(Collectors.toMap(TrainingAssignment::getId, a -> a, (a, b) -> a));

        // 学员姓名批量
        Map<UUID, String> learnerNames = new HashMap<>();
        userRepository.findAllById(assignments.stream().map(TrainingAssignment::getLearnerId)
                .distinct().toList())
                .forEach(u -> learnerNames.put(u.getId(), u.getName()));

        // 待复核
        Map<UUID, Integer> pendingCounts = new HashMap<>();
        if (!assignmentById.isEmpty()) {
            for (Object[] row : attemptRepository.findOldestPendingSubmitByAssignmentIds(assignmentById.keySet())) {
                pendingCounts.put((UUID) row[0], 1);
            }
        }
        List<TrainingAttempt> pendingAttempts = new ArrayList<>();
        if (!assignments.isEmpty()) {
            pendingAttempts.addAll(attemptRepository.findByAssignmentIdInAndStatusOrderBySubmittedAtAsc(
                    assignmentById.keySet(), TrainingAttempt.STATUS_PENDING_REVIEW));
            // 评分失败同样需要主管裁决，不能只出现在通知里
            pendingAttempts.addAll(attemptRepository.findByAssignmentIdInAndStatusOrderBySubmittedAtAsc(
                    assignmentById.keySet(), TrainingAttempt.STATUS_ERROR));
        }
        List<TrainingAttempt> pendingLimited = pendingAttempts.stream()
                .filter(a -> {
                    TrainingAssignment assignment = assignmentById.get(a.getAssignmentId());
                    return assignment != null && assignment.isOpen();
                })
                .sorted(Comparator.comparing(TrainingAttempt::getSubmittedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_PENDING).toList();
        Map<UUID, String> taskTitles = taskRepository.findAllById(
                        pendingLimited.stream().map(TrainingAttempt::getTaskId).distinct().toList())
                .stream().collect(Collectors.toMap(TrainingTask::getId, TrainingTask::getTitle));

        List<Map<String, Object>> pendingReviews = pendingLimited.stream().map(a -> {
            TrainingAssignment assignment = assignmentById.get(a.getAssignmentId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attemptId", a.getId().toString());
            m.put("assignmentId", a.getAssignmentId().toString());
            m.put("learnerName", assignment == null ? null
                    : learnerNames.get(assignment.getLearnerId()));
            m.put("cohortId", assignment == null ? null : assignment.getCohortId().toString());
            m.put("cohortName", assignment == null ? null
                    : cohortNames.get(assignment.getCohortId()));
            m.put("taskTitle", taskTitles.get(a.getTaskId()));
            m.put("type", a.getType());
            m.put("status", a.getStatus());
            m.put("attemptNo", a.getAttemptNo());
            m.put("submittedAt", a.getSubmittedAt() != null ? a.getSubmittedAt().toString() : null);
            return m;
        }).toList();

        // 待认证：学员必修已全过、主管还没确认，是培训收口最容易积压的一步
        List<Map<String, Object>> pendingCertifications = assignments.stream()
                .filter(a -> a.isOpen()
                        && TrainingAssignment.STATUS_PENDING_CERTIFICATION.equals(a.getStatus()))
                .sorted(Comparator.comparing(TrainingAssignment::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_PENDING)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("assignmentId", a.getId().toString());
                    m.put("learnerName", learnerNames.get(a.getLearnerId()));
                    m.put("cohortId", a.getCohortId().toString());
                    m.put("cohortName", cohortNames.get(a.getCohortId()));
                    m.put("requiredPassed", a.getRequiredPassed());
                    m.put("requiredTotal", a.getRequiredTotal());
                    m.put("updatedAt", a.getUpdatedAt() != null ? a.getUpdatedAt().toString() : null);
                    return m;
                }).toList();

        // 逾期学员
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> overdue = assignments.stream()
                .filter(a -> a.isOpen() && a.getDeadline() != null && a.getDeadline().isBefore(now))
                .sorted(Comparator.comparing(TrainingAssignment::getDeadline))
                .limit(MAX_OVERDUE)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("assignmentId", a.getId().toString());
                    m.put("learnerName", learnerNames.get(a.getLearnerId()));
                    m.put("cohortName", cohortNames.get(a.getCohortId()));
                    m.put("deadline", a.getDeadline() != null ? a.getDeadline().toString() : null);
                    m.put("requiredPassed", a.getRequiredPassed());
                    m.put("requiredTotal", a.getRequiredTotal());
                    return m;
                }).toList();

        // 批次概览
        Map<UUID, Long> certifiedByCohort = new HashMap<>();
        if (!assignments.isEmpty()) {
            Set<UUID> certifiedIds = certificationRepository
                    .findByAssignmentIdInAndStatus(assignments.stream().map(TrainingAssignment::getId).toList(),
                            "confirmed")
                    .stream().map(c -> c.getAssignmentId()).collect(Collectors.toSet());
            for (TrainingAssignment a : assignments) {
                if (certifiedIds.contains(a.getId())) {
                    certifiedByCohort.merge(a.getCohortId(), 1L, Long::sum);
                }
            }
        }
        List<Map<String, Object>> cohortViews = cohorts.stream().map(c -> {
            long total = assignments.stream().filter(a -> c.getId().equals(a.getCohortId())).count();
            long certified = certifiedByCohort.getOrDefault(c.getId(), 0L);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cohortId", c.getId().toString());
            m.put("name", cohortNames.get(c.getId()));
            m.put("status", c.getStatus());
            m.put("total", total);
            m.put("certified", certified);
            m.put("overdue", assignments.stream().filter(a -> c.getId().equals(a.getCohortId())
                    && a.isOpen() && a.getDeadline() != null && a.getDeadline().isBefore(now)).count());
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingReviews", pendingReviews);
        result.put("pendingCertifications", pendingCertifications);
        result.put("cohorts", cohortViews);
        result.put("overdueLearners", overdue);
        result.put("counts", Map.of(
                "pendingReview", pendingLimited.size(),
                "pendingCertification", pendingCertifications.size(),
                "overdue", overdue.size(),
                "pendingAssignments", pendingCounts.size()));
        return result;
    }
}
