package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.LeadActivityView;
import com.aiextract.dto.LeadCandidateView;
import com.aiextract.dto.LeadContactRequest;
import com.aiextract.dto.LeadContactView;
import com.aiextract.dto.LeadConvertRequest;
import com.aiextract.dto.LeadConvertResult;
import com.aiextract.dto.LeadDetailView;
import com.aiextract.dto.LeadSourceReviewView;
import com.aiextract.dto.LeadSummary;
import com.aiextract.dto.LeadUpdateRequest;
import com.aiextract.dto.TaskView;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.FollowUpTask;
import com.aiextract.model.Lead;
import com.aiextract.model.LeadActivity;
import com.aiextract.model.LeadContact;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSession;
import com.aiextract.model.User;
import com.aiextract.repository.FollowUpTaskRepository;
import com.aiextract.repository.LeadActivityRepository;
import com.aiextract.repository.LeadContactRepository;
import com.aiextract.repository.LeadRepository;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 线索：复盘转线索、列表、详情、联系人、时间线、状态流转。
 *
 * <p>转线索只做「人工确认」，不做线索池/认领；创建后负责人就是发起人。</p>
 */
@Service
@RequiredArgsConstructor
public class SalesLeadService {

    private final SalesAccessService access;
    private final ReviewSessionRepository sessionRepository;
    private final ReviewReportRepository reportRepository;
    private final LeadRepository leadRepository;
    private final LeadContactRepository contactRepository;
    private final LeadActivityRepository activityRepository;
    private final FollowUpTaskRepository taskRepository;
    private final UserRepository userRepository;
    private final LeadActivityService activityService;
    private final SalesAuditService audit;

    @Transactional
    public LeadConvertResult convertFromReview(UUID sessionId, LeadConvertRequest req) {
        // 先鉴权（避免跨租户拿着 ID 触发行锁），再锁会话把并发转线索串行化
        access.requireSession(sessionId);
        access.requireLeadManage();
        ReviewSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(404, "复盘不存在或不可访问"));
        if (session.getLeadId() != null) {
            Lead existing = leadRepository.findById(session.getLeadId())
                    .orElseThrow(() -> new BusinessException(404, "线索不存在"));
            return new LeadConvertResult(true, toSummary(existing, null), List.of());
        }
        // 必须是最新版 ready；v2 在途/失败时不能用 v1 转线索（P1-6）
        ReviewReport latest = reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId)
                .orElseThrow(() -> new BusinessException(409, "报告尚未生成，不能转线索"));
        if (!"ready".equals(latest.getStatus())) {
            throw new BusinessException(409, "最新报告尚未生成完成，不能转线索");
        }

        String name = req.name().trim();
        if (name.isEmpty()) {
            throw new BusinessException(400, "线索名称不能为空");
        }
        String normalized = normalizeName(name);
        String digits = digits(req.contactPhone());

        List<Lead> candidates = leadRepository.findDuplicates(
                session.getCompanyId(), normalized, digits, 5);
        if (!candidates.isEmpty() && !Boolean.TRUE.equals(req.force())) {
            Map<UUID, String> names = loadNames(
                    candidates.stream().map(Lead::getOwnerId).distinct().toList());
            return new LeadConvertResult(false, null, candidates.stream()
                    .map(c -> new LeadCandidateView(c.getId(), c.getName(),
                            names.get(c.getOwnerId()), c.getCreatedAt()))
                    .toList());
        }

        LocalDateTime now = LocalDateTime.now();
        Lead lead = Lead.builder()
                .id(UUID.randomUUID()).companyId(session.getCompanyId())
                .ownerId(session.getReviewerId()).name(name).nameNormalized(normalized)
                .contactName(trimToNull(req.contactName()))
                .contactPhone(trimToNull(req.contactPhone()))
                .contactPhoneDigits(digits).needSummary(trimToNull(req.needSummary()))
                .source("review").sourceReviewId(sessionId).status("new")
                .lastActivityAt(now)
                .createdBy(TokenContext.get()).createdAt(now).updatedAt(now).build();
        leadRepository.save(lead);

        String contactName = trimToNull(req.contactName());
        String contactPhone = trimToNull(req.contactPhone());
        if (contactName != null || contactPhone != null) {
            LeadContact contact = LeadContact.builder()
                    .id(UUID.randomUUID()).companyId(lead.getCompanyId()).leadId(lead.getId())
                    .name(contactName != null ? contactName : name)
                    .phone(contactPhone).phoneDigits(digits)
                    .role(LeadContact.ROLE_OTHER).primary(true)
                    .createdBy(TokenContext.get()).createdAt(now).updatedAt(now).build();
            contactRepository.save(contact);
        }

        session.setLeadId(lead.getId());
        session.setStatus("converted");
        session.setUpdatedAt(now);
        sessionRepository.save(session);

        // 先建任务后转线索：把之前 lead_id 为空的任务回填到新线索（P1-3）
        List<FollowUpTask> detachedTasks = taskRepository.findByReviewIdAndLeadIdIsNull(sessionId);
        if (!detachedTasks.isEmpty()) {
            detachedTasks.forEach(t -> t.setLeadId(lead.getId()));
            taskRepository.saveAll(detachedTasks);
        }

        activityService.record(lead.getId(), "created",
                "从复盘转为线索：" + name, "review_session", sessionId);
        if (!detachedTasks.isEmpty()) {
            activityService.record(lead.getId(), "task_created",
                    "关联复盘已生成任务：" + detachedTasks.size() + " 条", "review_session", sessionId);
        }
        audit.record("sales.lead.create", "lead", lead.getId(),
                Map.of("sessionId", sessionId.toString(), "name", name));
        return new LeadConvertResult(true, toSummary(lead, null), List.of());
    }

    public Page<LeadSummary> list(int page, int size, String status) {
        UUID companyId = access.requireCompanyId();
        Pageable p = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100));
        UUID me = TokenContext.get();
        String st = trimToNull(status);
        Page<Lead> rows = access.canViewAll()
                ? (st == null
                        ? leadRepository.findByCompanyIdOrderByLastActivityAtDesc(companyId, p)
                        : leadRepository.findByCompanyIdAndStatusOrderByLastActivityAtDesc(companyId, st, p))
                : (st == null
                        ? leadRepository.findByCompanyIdAndOwnerIdOrderByLastActivityAtDesc(companyId, me, p)
                        : leadRepository.findByCompanyIdAndOwnerIdAndStatusOrderByLastActivityAtDesc(
                                companyId, me, st, p));
        Map<UUID, String> names = loadNames(
                rows.getContent().stream().map(Lead::getOwnerId).distinct().toList());
        return rows.map(l -> toSummary(l, names.get(l.getOwnerId())));
    }

    public LeadDetailView detail(UUID id) {
        Lead lead = access.requireLead(id);
        List<LeadContactView> contacts = contactRepository
                .findByCompanyIdAndLeadIdOrderByPrimaryDescCreatedAtAsc(lead.getCompanyId(), id)
                .stream().map(this::toContactView).toList();
        List<LeadActivity> activityRows = activityRepository
                .findTop50ByCompanyIdAndLeadIdOrderByOccurredAtDesc(lead.getCompanyId(), id);
        Map<UUID, String> actorNames = loadNames(activityRows.stream()
                .map(LeadActivity::getActorId).filter(java.util.Objects::nonNull)
                .distinct().toList());
        List<LeadActivityView> activities = activityRows.stream()
                .map(a -> toActivityView(a, actorNames.get(a.getActorId()))).toList();

        List<FollowUpTask> taskRows = taskRepository
                .findTop50ByCompanyIdAndLeadIdOrderByDueAtDesc(lead.getCompanyId(), id);
        Map<UUID, String> taskOwnerNames = loadNames(taskRows.stream()
                .map(FollowUpTask::getOwnerId).distinct().toList());
        List<TaskView> tasks = taskRows.stream()
                .map(t -> toTaskView(t, taskOwnerNames.get(t.getOwnerId()))).toList();

        LeadSourceReviewView source = null;
        if (lead.getSourceReviewId() != null) {
            source = sessionRepository.findById(lead.getSourceReviewId())
                    .map(s -> new LeadSourceReviewView(s.getId(), s.getCustomerName(),
                            s.getTopic(), s.getCoachName(), s.getCreatedAt()))
                    .orElse(null);
        }
        return new LeadDetailView(toSummary(lead, ownerName(lead.getOwnerId())),
                lead.getNeedSummary(), lead.getNextFollowUpAt(), lead.getLastActivityAt(),
                contacts, activities, tasks, source);
    }

    /** 分页活动时间线，供详情页「加载更多」 */
    public Page<LeadActivityView> activities(UUID id, int page, int size) {
        Lead lead = access.requireLead(id);
        Pageable p = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100));
        Page<LeadActivity> rows = activityRepository
                .findByCompanyIdAndLeadIdOrderByOccurredAtDesc(lead.getCompanyId(), id, p);
        Map<UUID, String> names = loadNames(rows.getContent().stream()
                .map(LeadActivity::getActorId).filter(java.util.Objects::nonNull)
                .distinct().toList());
        return rows.map(a -> toActivityView(a, names.get(a.getActorId())));
    }

    /** 可分配的负责人列表（本企业启用成员） */
    public List<Map<String, Object>> listAssignableOwners() {
        access.requireLeadManage();
        UUID companyId = access.requireCompanyId();
        return userRepository.findByCompanyId(companyId).stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .map(u -> Map.<String, Object>of("id", u.getId().toString(), "name", u.getName()))
                .toList();
    }

    /** 分配线索负责人：目标用户必须属于同一企业 */
    @Transactional
    public LeadSummary assign(UUID id, UUID ownerId) {
        Lead lead = access.requireLead(id);
        access.requireLeadManage();
        User owner = userRepository.findById(ownerId)
                .filter(u -> java.util.Objects.equals(u.getCompanyId(), lead.getCompanyId()))
                .orElseThrow(() -> new BusinessException(404, "负责人不存在或不属于本企业"));
        if (!Boolean.TRUE.equals(owner.getIsActive())) {
            throw new BusinessException(400, "该成员账号已停用，不能分配线索");
        }
        UUID previous = lead.getOwnerId();
        lead.setOwnerId(ownerId);
        lead.setUpdatedAt(LocalDateTime.now());
        leadRepository.save(lead);
        activityService.record(id, "owner_changed", "线索已分配给 " + owner.getName(), "lead", id);
        audit.record("sales.lead.assign", "lead", id,
                Map.of("previousOwnerId", previous == null ? "" : previous.toString(),
                        "ownerId", ownerId.toString()));
        return toSummary(lead, owner.getName());
    }

    @Transactional
    public LeadSummary update(UUID id, LeadUpdateRequest req) {
        Lead lead = access.requireLead(id);
        access.requireLeadManage();
        String name = req.name().trim();
        if (name.isEmpty()) {
            throw new BusinessException(400, "线索名称不能为空");
        }
        String normalized = normalizeName(name);
        List<Lead> duplicates = leadRepository.findDuplicatesExcluding(
                lead.getCompanyId(), normalized, null, id, 5);
        if (!duplicates.isEmpty()) {
            throw BusinessException.conflict("LEAD_DUPLICATE", "已有同名线索");
        }
        lead.setName(name);
        lead.setNameNormalized(normalized);
        lead.setNeedSummary(trimToNull(req.needSummary()));
        lead.setNextFollowUpAt(req.nextFollowUpAt());
        lead.setUpdatedAt(LocalDateTime.now());
        leadRepository.save(lead);
        activityService.record(id, "field_updated", "更新线索资料", "lead", id);
        audit.record("sales.lead.update", "lead", id, Map.of("name", name));
        return toSummary(lead, ownerName(lead.getOwnerId()));
    }

    @Transactional
    public LeadContactView addContact(UUID leadId, LeadContactRequest req) {
        Lead lead = access.requireLead(leadId);
        access.requireLeadManage();
        String name = req.name().trim();
        boolean primary = Boolean.TRUE.equals(req.primary());
        LocalDateTime now = LocalDateTime.now();
        if (primary) {
            contactRepository.clearPrimary(leadId, now);
        }
        LeadContact contact = LeadContact.builder()
                .id(UUID.randomUUID()).companyId(lead.getCompanyId()).leadId(leadId)
                .name(name).phone(trimToNull(req.phone())).phoneDigits(digits(req.phone()))
                .role(req.role() == null ? LeadContact.ROLE_OTHER : req.role())
                .primary(primary).note(trimToNull(req.note()))
                .createdBy(TokenContext.get()).createdAt(now).updatedAt(now).build();
        saveContact(contact);
        if (primary) {
            syncPrimaryToLead(lead, contact);
        }
        activityService.record(leadId, "contact_added",
                "新增联系人 " + name, "lead_contact", contact.getId());
        return toContactView(contact);
    }

    @Transactional
    public LeadContactView updateContact(UUID leadId, UUID contactId, LeadContactRequest req) {
        Lead lead = access.requireLead(leadId);
        access.requireLeadManage();
        LeadContact contact = contactRepository
                .findByIdAndCompanyIdAndLeadId(contactId, lead.getCompanyId(), leadId)
                .orElseThrow(() -> new BusinessException(404, "联系人不存在"));
        boolean primary = Boolean.TRUE.equals(req.primary());
        LocalDateTime now = LocalDateTime.now();
        if (primary && !contact.isPrimary()) {
            contactRepository.clearPrimary(leadId, now);
        }
        contact.setName(req.name().trim());
        contact.setPhone(trimToNull(req.phone()));
        contact.setPhoneDigits(digits(req.phone()));
        contact.setRole(req.role() == null ? LeadContact.ROLE_OTHER : req.role());
        contact.setPrimary(primary);
        contact.setNote(trimToNull(req.note()));
        contact.setUpdatedAt(now);
        saveContact(contact);
        syncLeadPrimarySnapshot(lead);
        activityService.record(leadId, "contact_updated",
                "更新联系人 " + contact.getName(), "lead_contact", contactId);
        return toContactView(contact);
    }

    @Transactional
    public void deleteContact(UUID leadId, UUID contactId) {
        Lead lead = access.requireLead(leadId);
        access.requireLeadManage();
        LeadContact contact = contactRepository
                .findByIdAndCompanyIdAndLeadId(contactId, lead.getCompanyId(), leadId)
                .orElseThrow(() -> new BusinessException(404, "联系人不存在"));
        contactRepository.delete(contact);
        syncLeadPrimarySnapshot(lead);
        activityService.record(leadId, "contact_removed",
                "删除联系人 " + contact.getName(), "lead_contact", contactId);
    }

    @Transactional
    public LeadActivityView addNote(UUID leadId, String content) {
        access.requireLead(leadId);
        access.requireLeadManage();
        String text = content.trim();
        if (text.isEmpty()) {
            throw new BusinessException(400, "备注不能为空");
        }
        LeadActivity activity = activityService.record(leadId, "note", text, "lead", leadId);
        return toActivityView(activity, ownerName(activity.getActorId()));
    }

    @Transactional
    public LeadSummary updateStatus(UUID id, String status) {
        Lead lead = access.requireLead(id);
        access.requireLeadManage();
        if (!Set.of("new", "following", "converted", "invalid").contains(status)) {
            throw new BusinessException(400, "非法线索状态");
        }
        String from = lead.getStatus();
        boolean allowed = switch (from) {
            case "new" -> Set.of("following", "invalid").contains(status);
            case "following" -> Set.of("converted", "invalid").contains(status);
            case "converted" -> "invalid".equals(status);
            case "invalid" -> "new".equals(status);
            default -> false;
        };
        if (!allowed) {
            throw BusinessException.conflict("LEAD_STATE_INVALID", "线索状态不能这样流转");
        }
        LocalDateTime now = LocalDateTime.now();
        if (leadRepository.updateStatusIfCurrent(id, from, status, now) == 0) {
            throw BusinessException.conflict("LEAD_STATE_CHANGED", "线索状态已被其他操作修改，请刷新后重试");
        }
        lead.setStatus(status);
        lead.setUpdatedAt(now);
        lead.setLastActivityAt(now);
        activityService.record(lead.getId(), "status_changed",
                "状态 " + from + " → " + status, "lead", lead.getId());
        audit.record("sales.lead.status", "lead", lead.getId(),
                Map.of("from", from, "to", status));
        return toSummary(lead, ownerName(lead.getOwnerId()));
    }

    private void saveContact(LeadContact contact) {
        try {
            contactRepository.saveAndFlush(contact);
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("LEAD_CONTACT_PRIMARY_CONFLICT",
                    "主联系人已被其他操作修改，请刷新重试");
        }
    }

    /** 设置主联系人时同步线索快照 */
    private void syncPrimaryToLead(Lead lead, LeadContact contact) {
        lead.setContactName(contact.getName());
        lead.setContactPhone(contact.getPhone());
        lead.setContactPhoneDigits(contact.getPhoneDigits());
        lead.setUpdatedAt(LocalDateTime.now());
        leadRepository.save(lead);
    }

    /** 任意联系人变更后，按当前主联系人重建线索快照 */
    private void syncLeadPrimarySnapshot(Lead lead) {
        LeadContact primary = contactRepository
                .findByCompanyIdAndLeadIdOrderByPrimaryDescCreatedAtAsc(
                        lead.getCompanyId(), lead.getId())
                .stream().filter(LeadContact::isPrimary).findFirst().orElse(null);
        lead.setContactName(primary == null ? null : primary.getName());
        lead.setContactPhone(primary == null ? null : primary.getPhone());
        lead.setContactPhoneDigits(primary == null ? null : primary.getPhoneDigits());
        lead.setUpdatedAt(LocalDateTime.now());
        leadRepository.save(lead);
    }

    private LeadSummary toSummary(Lead l, String ownerName) {
        return new LeadSummary(l.getId(), l.getName(), l.getOwnerId().toString(), ownerName,
                l.getStatus(), l.getSource(), l.getSourceReviewId(),
                l.getNeedSummary(), l.getNextFollowUpAt(), l.getLastActivityAt(), l.getUpdatedAt());
    }

    private LeadContactView toContactView(LeadContact c) {
        return new LeadContactView(c.getId(), c.getName(), c.getPhone(), c.getRole(),
                c.isPrimary(), c.getNote(), c.getCreatedAt());
    }

    private LeadActivityView toActivityView(LeadActivity a, String actorName) {
        return new LeadActivityView(a.getId(), a.getType(), a.getContent(), actorName,
                a.getSourceType(), a.getSourceId(), a.getOccurredAt());
    }

    private TaskView toTaskView(FollowUpTask t, String ownerName) {
        return new TaskView(t.getId(), t.getLeadId(), t.getReviewId(), t.getOwnerId(),
                ownerName, t.getTitle(), t.getChannel(), t.getContent(), t.getDueAt(),
                t.getStatus(), t.getCompletedAt(), t.getEscalatedAt());
    }

    private String ownerName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(u -> u.getName()).orElse(null);
    }

    private Map<UUID, String> loadNames(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> out.put(u.getId(), u.getName()));
        return out;
    }

    private String normalizeName(String name) {
        // NFKC 把全角字母/数字/标点折叠成半角，再做标点与空白清理
        String normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC);
        return normalized.replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}，。；：、（）【】《》]", "")
                .toLowerCase();
    }

    private String digits(String phone) {
        if (phone == null) {
            return null;
        }
        String d = phone.replaceAll("\\D", "");
        if (d.length() > 30) {
            throw new BusinessException(400, "联系电话过长，最多 30 位数字");
        }
        return d.isEmpty() ? null : d;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
