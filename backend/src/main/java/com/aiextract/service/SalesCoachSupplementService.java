package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.CoachNoteView;
import com.aiextract.dto.CoachRequestCreateRequest;
import com.aiextract.dto.CoachRequestView;
import com.aiextract.dto.CoachSupplementView;
import com.aiextract.dto.CoachWorkspaceView;
import com.aiextract.dto.ReviewReportView;
import com.aiextract.dto.ReviewSegmentView;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ReviewCoachNote;
import com.aiextract.model.ReviewCoachRequest;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSession;
import com.aiextract.model.User;
import com.aiextract.repository.ReviewCoachNoteRepository;
import com.aiextract.repository.ReviewCoachRequestRepository;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSegmentRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 真人教练补充：请求 → 通知 → 教练写补充 → 通知发起人。
 *
 * <p>补充与 AI 报告分表存，不改报告版本，也不参与报告重生成。</p>
 */
@Service
@RequiredArgsConstructor
public class SalesCoachSupplementService {

    private final SalesAccessService access;
    private final ReviewSessionRepository sessionRepository;
    private final ReviewReportRepository reportRepository;
    private final ReviewSegmentRepository segmentRepository;
    private final ReviewCoachRequestRepository requestRepository;
    private final ReviewCoachNoteRepository noteRepository;
    private final UserRepository userRepository;
    private final TrainingNotificationService notificationService;
    private final SalesAuditService audit;
    private final ObjectMapper objectMapper;

    @Transactional
    public CoachSupplementView request(UUID sessionId, CoachRequestCreateRequest req) {
        access.requireSession(sessionId);
        // 锁会话：并发重复点「请教练补充」时只允许一个请求写入（session_id 唯一）
        ReviewSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(404, "复盘不存在或不可访问"));
        if (!session.getReviewerId().equals(TokenContext.get())) {
            throw new BusinessException(403, "只有复盘发起人能给教练发请求");
        }
        if ("closed".equals(session.getStatus())) {
            throw new BusinessException(409, "复盘已关闭");
        }
        LocalDateTime now = LocalDateTime.now();
        if (session.getCoachOwnerId().equals(TokenContext.get())) {
            return supplementView(sessionId);
        }
        ReviewCoachRequest r = requestRepository.findBySessionId(sessionId)
                .orElseGet(() -> ReviewCoachRequest.builder()
                        .id(UUID.randomUUID()).companyId(session.getCompanyId())
                        .sessionId(sessionId).coachUserId(session.getCoachOwnerId())
                        .requestedBy(TokenContext.get()).createdAt(now).build());
        r.setMessage(trimToNull(req.message()));
        r.setStatus("pending");
        r.setRequestedAt(now);
        r.setCompletedAt(null);
        r.setUpdatedAt(now);
        requestRepository.save(r);

        boolean share = Boolean.TRUE.equals(req.shareTranscript());
        if (share && !session.isShareTranscriptWithCoach()) {
            session.setShareTranscriptWithCoach(true);
            session.setUpdatedAt(now);
            sessionRepository.save(session);
            audit.record("sales.review.share", "review_session", sessionId, Map.of("share", true));
        }
        audit.record("sales.coach.request", "review_coach_request", r.getId(),
                Map.of("sessionId", sessionId.toString(), "shareTranscript", share));

        notificationService.notifyUser(session.getCoachOwnerId(), session.getCompanyId(),
                "sales_coach_request", "有人请你补充复盘",
                nameOf(session.getReviewerId()) + " 请你看一下「"
                        + (session.getCustomerName() == null ? "本次对话" : session.getCustomerName())
                        + "」的复盘。",
                "/sales/coach/requests/" + sessionId, "review_coach_request", r.getId(),
                "sales:coach-request:" + r.getId() + ":" + now.toEpochSecond(ZoneOffset.UTC));
        return supplementView(sessionId);
    }

    @Transactional
    public void cancel(UUID sessionId) {
        ReviewSession session = access.requireSession(sessionId);
        if (!session.getReviewerId().equals(TokenContext.get())) {
            throw new BusinessException(403, "只有发起人能撤回请求");
        }
        ReviewCoachRequest r = requestRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(404, "没有待补充请求"));
        if (!"pending".equals(r.getStatus())) {
            return;
        }
        r.setStatus("cancelled");
        r.setUpdatedAt(LocalDateTime.now());
        requestRepository.save(r);
        audit.record("sales.coach.cancel", "review_coach_request", r.getId(),
                Map.of("sessionId", sessionId.toString()));
    }

    public CoachSupplementView supplementView(UUID sessionId) {
        ReviewSession session = access.requireSession(sessionId);
        ReviewCoachRequest r = requestRepository.findBySessionId(sessionId).orElse(null);
        List<ReviewCoachNote> noteRows = noteRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId);
        Map<UUID, String> noteNames = namesOf(noteRows.stream()
                .map(ReviewCoachNote::getCoachUserId).distinct().toList());
        List<CoachNoteView> notes = noteRows.stream()
                .map(n -> new CoachNoteView(n.getId(), noteNames.get(n.getCoachUserId()),
                        n.getContent(), n.getCreatedAt()))
                .toList();
        boolean selfCoach = session.getCoachOwnerId() != null
                && session.getCoachOwnerId().equals(TokenContext.get());
        return new CoachSupplementView(r == null ? null : r.getStatus(),
                r == null ? null : r.getMessage(), session.isShareTranscriptWithCoach(),
                selfCoach, notes);
    }

    public Page<CoachRequestView> listMine(int page, int size, String status) {
        return list(page, size, status, true);
    }

    /**
     * 教练台账列表。mine=true 只看指派给自己的；mine=false 需 SALES_REVIEW_VIEW_ALL（管理员看全企业）。
     */
    public Page<CoachRequestView> list(int page, int size, String status, boolean mine) {
        UUID me = TokenContext.get();
        UUID companyId = access.requireCompanyId();
        boolean viewAll = !mine && access.canViewAll();
        Pageable p = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100));
        String st = trimToNull(status);
        Page<ReviewCoachRequest> rows;
        if (viewAll) {
            rows = st == null
                    ? requestRepository.findByCompanyIdOrderByRequestedAtDesc(companyId, p)
                    : requestRepository.findByCompanyIdAndStatusOrderByRequestedAtDesc(companyId, st, p);
        } else {
            rows = st == null
                    ? requestRepository.findByCompanyIdAndCoachUserIdOrderByRequestedAtDesc(companyId, me, p)
                    : requestRepository.findByCompanyIdAndCoachUserIdAndStatusOrderByRequestedAtDesc(
                            companyId, me, st, p);
        }
        List<UUID> sessionIds = rows.getContent().stream()
                .map(ReviewCoachRequest::getSessionId).toList();
        Map<UUID, ReviewSession> sessions = new HashMap<>();
        sessionRepository.findAllById(sessionIds).forEach(s -> sessions.put(s.getId(), s));
        List<UUID> userIds = new java.util.ArrayList<>();
        rows.getContent().forEach(r -> {
            userIds.add(r.getRequestedBy());
            userIds.add(r.getCoachUserId());
        });
        Map<UUID, String> names = namesOf(userIds.stream().filter(java.util.Objects::nonNull).distinct().toList());
        return rows.map(r -> toView(r, sessions.get(r.getSessionId()), names));
    }

    /** SA-08 标记已读（批量）。只允许指派教练或管理员操作。 */
    @Transactional
    public int markReadBatch(List<UUID> ids) {
        LocalDateTime now = LocalDateTime.now();
        int updated = 0;
        for (UUID id : ids) {
            ReviewCoachRequest r = requireTask(id);
            if (r.getReadAt() == null) {
                r.setReadAt(now);
                r.setUpdatedAt(now);
                requestRepository.save(r);
                updated++;
            }
        }
        return updated;
    }

    /** SA-08 回复（单条）：落回复内容 → 状态 done → 通知发起人。 */
    @Transactional
    public CoachRequestView reply(UUID id, String content) {
        return replyInternal(requireTask(id), content);
    }

    /** SA-08 批量回复：同一内容回复多条待处理请求；逐条校验权限，单条失败不影响其他。 */
    @Transactional
    public Map<String, Object> replyBatch(List<UUID> ids, String content) {
        int ok = 0;
        List<Map<String, Object>> failures = new java.util.ArrayList<>();
        for (UUID id : ids) {
            try {
                replyInternal(requireTask(id), content);
                ok++;
            } catch (BusinessException e) {
                failures.add(Map.of("id", id.toString(), "message", e.getMessage()));
            }
        }
        return Map.of("success", ok, "failed", failures.size(), "failures", failures);
    }

    /** SA-08 转交：改指派的教练，重置为待处理并通知新教练。 */
    @Transactional
    public CoachRequestView transfer(UUID id, UUID newCoachId) {
        ReviewCoachRequest r = requireTask(id);
        if (newCoachId == null) {
            throw new BusinessException(400, "请选择转交对象");
        }
        User target = userRepository.findById(newCoachId)
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .filter(u -> r.getCompanyId().equals(u.getCompanyId()))
                .orElseThrow(() -> new BusinessException(400, "转交对象不存在或不属于本企业"));
        LocalDateTime now = LocalDateTime.now();
        r.setCoachUserId(newCoachId);
        r.setStatus("pending");
        r.setReadAt(null);
        r.setRepliedAt(null);
        r.setReplyContent(null);
        r.setCompletedAt(null);
        r.setRequestedAt(now);
        r.setUpdatedAt(now);
        requestRepository.save(r);
        audit.record("sales.coach.transfer", "review_coach_request", r.getId(),
                Map.of("newCoachId", newCoachId.toString()));
        notificationService.notifyUser(newCoachId, r.getCompanyId(),
                "sales_coach_request", "有人转交了一份复盘请求",
                nameOf(r.getRequestedBy()) + " 的复盘请求已转交给你。",
                "/sales/coach/requests/" + r.getSessionId(), "review_coach_request", r.getId(),
                "sales:coach-transfer:" + r.getId() + ":" + now.toEpochSecond(ZoneOffset.UTC));
        ReviewSession s = sessionRepository.findById(r.getSessionId()).orElse(null);
        return toView(r, s, namesOf(List.of(r.getRequestedBy(), newCoachId)));
    }

    /** 可转交的同事列表（同企业、启用中） */
    public List<Map<String, Object>> listCoaches() {
        UUID companyId = access.requireCompanyId();
        return userRepository.findByCompanyId(companyId).stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .filter(u -> !u.getId().equals(TokenContext.get()))
                .limit(100)
                .map(u -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", u.getId().toString());
                    m.put("name", u.getName());
                    m.put("role", u.getRole());
                    return m;
                })
                .toList();
    }

    private CoachRequestView replyInternal(ReviewCoachRequest r, String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BusinessException(400, "回复内容不能为空");
        }
        if ("cancelled".equals(r.getStatus())) {
            throw new BusinessException(409, "请求已撤回，不能回复");
        }
        LocalDateTime now = LocalDateTime.now();
        r.setReplyContent(text);
        r.setRepliedAt(now);
        r.setCompletedAt(now);
        r.setStatus("done");
        if (r.getReadAt() == null) {
            r.setReadAt(now);
        }
        r.setUpdatedAt(now);
        requestRepository.save(r);
        ReviewSession s = sessionRepository.findById(r.getSessionId()).orElse(null);
        String customer = s == null || s.getCustomerName() == null ? "本次对话" : s.getCustomerName();
        notificationService.notifyUser(r.getRequestedBy(), r.getCompanyId(),
                "sales_coach_reply", "教练回复了你的复盘请求",
                nameOf(TokenContext.get()) + " 回复了「" + customer + "」的复盘。",
                "/sales/reviews/" + r.getSessionId(), "review_coach_request", r.getId(),
                "sales:coach-reply:" + r.getId() + ":" + now.toEpochSecond(ZoneOffset.UTC));
        audit.record("sales.coach.reply", "review_coach_request", r.getId(),
                Map.of("chars", text.length()));
        return toView(r, s, namesOf(List.of(r.getRequestedBy(), r.getCoachUserId())));
    }

    /** 任务操作权限：指派教练或管理员（VIEW_ALL） */
    private ReviewCoachRequest requireTask(UUID id) {
        ReviewCoachRequest r = requestRepository.findByIdAndCompanyId(id, access.requireCompanyId())
                .orElseThrow(() -> new BusinessException(404, "教练请求不存在"));
        if (!access.canViewAll() && !r.getCoachUserId().equals(TokenContext.get())) {
            throw new BusinessException(404, "教练请求不存在");
        }
        return r;
    }

    private CoachRequestView toView(ReviewCoachRequest r, ReviewSession s, Map<UUID, String> names) {
        return new CoachRequestView(r.getId(), r.getSessionId(),
                s == null ? null : names.get(s.getReviewerId()),
                names.get(r.getCoachUserId()),
                s == null ? null : s.getCustomerName(),
                s == null ? null : s.getTopic(),
                r.getMessage(), r.getStatus(), r.getRequestedAt(), r.getCompletedAt(),
                r.getReadAt(), r.getRepliedAt(), r.getReplyContent());
    }

    public CoachWorkspaceView workspace(UUID sessionId) {
        UUID me = TokenContext.get();
        ReviewSession s = sessionRepository.findById(sessionId)
                .filter(x -> x.getCompanyId().equals(access.requireCompanyId()))
                .orElseThrow(() -> new BusinessException(404, "复盘不存在或不可访问"));
        ReviewCoachRequest r = requestRepository.findBySessionId(sessionId).orElse(null);
        boolean self = s.getCoachOwnerId().equals(me) && s.getReviewerId().equals(me);
        if (!s.getCoachOwnerId().equals(me) || (r == null && !self)) {
            throw new BusinessException(404, "复盘不存在或不可访问");
        }
        ReviewReport latest = reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId)
                .orElse(null);
        List<ReviewSegmentView> segments = s.isShareTranscriptWithCoach()
                ? segmentRepository.findBySessionIdOrderBySeqAsc(sessionId).stream()
                        .map(x -> new ReviewSegmentView(x.getId(), x.getSeq(), x.getSpeaker(), x.getText()))
                        .toList()
                : List.of();
        List<ReviewCoachNote> noteRows = noteRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId);
        Map<UUID, String> noteNames = namesOf(noteRows.stream()
                .map(ReviewCoachNote::getCoachUserId).distinct().toList());
        List<CoachNoteView> notes = noteRows.stream()
                .map(n -> new CoachNoteView(n.getId(), noteNames.get(n.getCoachUserId()),
                        n.getContent(), n.getCreatedAt()))
                .toList();
        return new CoachWorkspaceView(sessionId, nameOf(s.getReviewerId()),
                s.getCustomerName(), s.getTopic(), s.isShareTranscriptWithCoach(),
                latest == null ? null : toReportView(latest), segments, notes,
                r == null ? null : r.getStatus(), r == null ? null : r.getMessage());
    }

    @Transactional
    public CoachNoteView addNote(UUID sessionId, String content) {
        UUID me = TokenContext.get();
        ReviewSession s = sessionRepository.findById(sessionId)
                .filter(x -> x.getCompanyId().equals(access.requireCompanyId()))
                .orElseThrow(() -> new BusinessException(404, "复盘不存在或不可访问"));
        ReviewCoachRequest r = requestRepository.findBySessionId(sessionId).orElse(null);
        boolean self = s.getCoachOwnerId().equals(me) && s.getReviewerId().equals(me);
        if (!s.getCoachOwnerId().equals(me) || (r == null && !self)) {
            throw new BusinessException(404, "复盘不存在或不可访问");
        }
        if ("closed".equals(s.getStatus())) {
            throw new BusinessException(409, "复盘已关闭");
        }
        if (r != null && "cancelled".equals(r.getStatus())) {
            throw new BusinessException(409, "教练请求已取消，不能再补充");
        }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BusinessException(400, "补充内容不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        ReviewCoachNote note = noteRepository.save(ReviewCoachNote.builder()
                .id(UUID.randomUUID()).companyId(s.getCompanyId()).sessionId(sessionId)
                .coachUserId(me).content(text).createdAt(now).build());

        if (r != null && "pending".equals(r.getStatus())) {
            r.setStatus("done");
            r.setCompletedAt(now);
            r.setUpdatedAt(now);
            requestRepository.save(r);
            if (!s.getReviewerId().equals(me)) {
                notificationService.notifyUser(s.getReviewerId(), s.getCompanyId(),
                        "sales_coach_note", "教练补充了复盘",
                        nameOf(me) + " 补充了「"
                                + (s.getCustomerName() == null ? "本次对话" : s.getCustomerName())
                                + "」的复盘。",
                        "/sales/reviews/" + sessionId, "review_coach_note", note.getId(),
                        "sales:coach-note:" + note.getId());
            }
        }
        audit.record("sales.coach.note", "review_coach_note", note.getId(),
                Map.of("sessionId", sessionId.toString(), "chars", text.length()));
        return new CoachNoteView(note.getId(), nameOf(me), text, now);
    }

    private ReviewReportView toReportView(ReviewReport r) {
        return com.aiextract.dto.ReviewReportViewMapper.toView(r, objectMapper);
    }

    private String nameOf(UUID userId) {
        if (userId == null) {
            return "同事";
        }
        return userRepository.findById(userId).map(User::getName).orElse("同事");
    }

    private Map<UUID, String> namesOf(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> out.put(u.getId(), u.getName()));
        return out;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
