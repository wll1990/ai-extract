package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.CoachOptionView;
import com.aiextract.dto.ReviewCreateRequest;
import com.aiextract.dto.ReviewDetailView;
import com.aiextract.dto.ReviewReportView;
import com.aiextract.dto.ReviewSegmentView;
import com.aiextract.dto.ReviewSessionView;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Lead;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSegment;
import com.aiextract.model.ReviewSession;
import com.aiextract.model.Skill;
import com.aiextract.model.Space;
import com.aiextract.model.User;
import com.aiextract.repository.FollowUpTaskRepository;
import com.aiextract.repository.LeadRepository;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSegmentRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 复盘会话的创建、列表、详情与报告触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesReviewService {

    private final SalesAccessService access;
    private final ReviewSessionRepository sessionRepository;
    private final ReviewSegmentRepository segmentRepository;
    private final ReviewReportRepository reportRepository;
    private final SalesReviewReportService reportService;
    private final FollowUpTaskRepository taskRepository;
    private final LeadRepository leadRepository;
    private final SkillRepository skillRepository;
    private final SpaceRepository spaceRepository;
    private final com.aiextract.repository.ExperienceGrainRepository grainRepository;
    private final UserRepository userRepository;
    private final ReviewSegmenter segmenter;
    private final SalesAuditService audit;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;
    private final FileParseService fileParseService;
    private final KnowledgeCaseService knowledgeCaseService;

    @Value("${app.sales.review.max-transcript-chars:30000}")
    private int maxTranscriptChars;

    /**
     * 复盘文件解析：存文件 → 调 ai-service 提取纯文本。
     *
     * <p>只返回文本，不落复盘记录：前端先预览、可编辑，确认后再提交 create。</p>
     */
    public String parseTranscriptFile(org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的文件");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(400, "文件名不能为空");
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".txt") || lower.endsWith(".docx") || lower.endsWith(".pdf"))) {
            throw new BusinessException(400, "复盘文件仅支持 txt / docx / pdf");
        }
        String objectKey = fileStorageService.saveFile(file, "reviews");
        String text = fileParseService.parseToText(objectKey, fileName);
        if (text == null || text.isBlank()) {
            throw new BusinessException(400, "未能从文件中解析出文字，请手动粘贴对话内容");
        }
        if (text.length() > maxTranscriptChars) {
            throw new BusinessException(400, "解析出的文本超过 " + maxTranscriptChars + " 字，请拆分后上传");
        }
        return text;
    }

    /**
     * 复盘沉淀：把「结论 + 详情 + 对话原文」组装成知识/案例草稿（pending），
     * 由管理员在对应详情页确认后审批生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> precipitate(UUID sessionId, String target) {
        ReviewSession session = access.requireSession(sessionId);
        // 员工可以提交草稿（KNOWLEDGE_SUBMIT），审批仍由 KNOWLEDGE_MANAGE 完成
        if (!access.hasAuthority(com.aiextract.config.Permission.KNOWLEDGE_SUBMIT)
                && !access.hasAuthority(com.aiextract.config.Permission.KNOWLEDGE_MANAGE)) {
            throw new BusinessException(403, "无提交知识/案例的权限");
        }
        ReviewReport report = reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId)
                .orElseThrow(() -> new BusinessException(409, "复盘报告尚未生成，暂不能沉淀"));
        // 报告完成态是 ready（沿用 ReviewReport 既有状态）；保留 completed 兼容历史数据
        if (!"ready".equals(report.getStatus()) && !"completed".equals(report.getStatus())) {
            throw new BusinessException(409, "复盘报告尚在生成中，请稍后再试");
        }
        String baseTitle = session.getCustomerName() != null && !session.getCustomerName().isBlank()
                ? session.getCustomerName()
                : (session.getTopic() != null && !session.getTopic().isBlank()
                        ? session.getTopic() : "销售复盘");
        if (baseTitle.length() > 180) {
            baseTitle = baseTitle.substring(0, 180);
        }
        if ("knowledge".equalsIgnoreCase(target)) {
            com.aiextract.dto.KnowledgeEntryRequest req = com.aiextract.dto.KnowledgeEntryRequest.builder()
                    .title(baseTitle + " · 复盘沉淀")
                    .category(com.aiextract.model.KnowledgeEntry.CATEGORY_METHODOLOGY)
                    .sourceType(com.aiextract.model.KnowledgeEntry.SOURCE_TYPE_TENANT)
                    .content(knowledgeDigest(report, session))
                    .expireAt(LocalDateTime.now().plusYears(1))
                    .source("销售复盘：" + sessionId)
                    .status(com.aiextract.model.KnowledgeEntry.STATUS_PENDING)
                    .build();
            com.aiextract.model.KnowledgeEntry k =
                    knowledgeCaseService.createKnowledge(req, session.getCompanyId());
            audit.record("sales.review.precipitate", "knowledge", k.getId(),
                    Map.of("sessionId", sessionId.toString()));
            return Map.of("type", "knowledge", "id", k.getId().toString());
        }
        if ("case".equalsIgnoreCase(target)) {
            com.aiextract.dto.IndustryCaseRequest req = com.aiextract.dto.IndustryCaseRequest.builder()
                    .title(baseTitle + " · 复盘沉淀")
                    .result("delay")
                    .objection(report.getSummary())
                    .reusablePoints(report.getSummary())
                    .scripts(session.getTranscript() != null && session.getTranscript().length() > 5000
                            ? session.getTranscript().substring(0, 5000) : session.getTranscript())
                    .source("销售复盘：" + sessionId)
                    .status(com.aiextract.model.IndustryCase.STATUS_PENDING)
                    .build();
            com.aiextract.model.IndustryCase c =
                    knowledgeCaseService.createCase(req, session.getCompanyId());
            audit.record("sales.review.precipitate", "case", c.getId(),
                    Map.of("sessionId", sessionId.toString()));
            return Map.of("type", "case", "id", c.getId().toString());
        }
        throw new BusinessException(400, "target 仅支持 knowledge / case");
    }

    /**
     * 复盘沉淀为知识的正文：只保留结论 + 主要问题/建议 + 来源，
     * 控制在知识准入上限内；对话原文留在复盘里，不再整段塞进知识卡。
     */
    private String knowledgeDigest(ReviewReport report, ReviewSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 复盘结论\n")
                .append(report.getSummary() == null ? "" : report.getSummary()).append("\n\n");
        List<String> points = new ArrayList<>();
        if (report.getPayload() != null && !report.getPayload().isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(report.getPayload());
                collectDigestPoints(points, root.path("weaknesses"), 4);
                collectDigestPoints(points, root.path("risks"), 3);
            } catch (Exception e) {
                log.warn("复盘沉淀解析报告失败 sessionId={}: {}", session.getId(), e.getMessage());
            }
        }
        if (!points.isEmpty()) {
            sb.append("## 主要问题与建议\n");
            for (String p : points) {
                sb.append("- ").append(p).append('\n');
            }
            sb.append('\n');
        }
        String customer = session.getCustomerName() != null && !session.getCustomerName().isBlank()
                ? session.getCustomerName() : (session.getTopic() == null ? "未命名客户" : session.getTopic());
        sb.append("## 来源\n销售复盘：").append(customer)
                .append("（").append(session.getId()).append("）\n");
        String content = sb.toString();
        return content.length() <= 1400 ? content : content.substring(0, 1400) + "…";
    }

    private void collectDigestPoints(List<String> out,
                                     com.fasterxml.jackson.databind.JsonNode arr, int max) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        int added = 0;
        for (com.fasterxml.jackson.databind.JsonNode n : arr) {
            if (added >= max) {
                break;
            }
            String point = n.path("point").isTextual() ? n.path("point").asText().trim() : null;
            if (point == null || point.isEmpty()) {
                continue;
            }
            String suggestion = n.path("suggestion").isTextual()
                    ? n.path("suggestion").asText().trim() : null;
            out.add(suggestion == null || suggestion.isEmpty()
                    ? point : point + "（建议：" + suggestion + "）");
            added++;
        }
    }

    @Transactional
    public ReviewDetailView create(ReviewCreateRequest req) {
        UUID companyId = access.requireCompanyId();
        String transcript = normalizeText(req.transcript());
        if (transcript.length() > maxTranscriptChars) {
            throw new BusinessException(400, "对话文本不能超过 " + maxTranscriptChars + " 字");
        }
        Skill coach = skillRepository.findById(req.coachSkillId())
                .filter(s -> "individual".equals(s.getType()))
                .filter(s -> "published".equals(s.getStatus()))
                .orElseThrow(() -> new BusinessException(404, "教练分身不存在或不可用"));
        if (coach.getSpaceId() == null) {
            throw new BusinessException(404, "教练分身归属异常");
        }
        Space space = spaceRepository.findById(coach.getSpaceId())
                .orElseThrow(() -> new BusinessException(404, "教练分身归属异常"));
        UUID coachCompanyId = coach.getCompanyId() != null
                ? coach.getCompanyId()
                : userRepository.findById(space.getUserId())
                        .map(User::getCompanyId).orElse(null);
        if (!companyId.equals(coachCompanyId)) {
            throw new BusinessException(404, "教练分身不存在或不可用");
        }

        // 关联已有客户时，客户名以线索为准（前端选择器会自动带出，这里兜底）
        Lead linkedLead = req.leadId() == null ? null : access.requireLead(req.leadId());
        String customerName = trimToNull(req.customerName());
        if (customerName == null && linkedLead != null) {
            customerName = linkedLead.getName();
        }
        List<ReviewSegmenter.SegmentDraft> drafts = segmenter.split(transcript);
        LocalDateTime now = LocalDateTime.now();
        ReviewSession session = ReviewSession.builder()
                .id(UUID.randomUUID()).companyId(companyId).reviewerId(TokenContext.get())
                .coachSkillId(coach.getId()).coachOwnerId(space.getUserId())
                .coachName(coach.getDisplayName() != null ? coach.getDisplayName() : coach.getOwnerName())
                .coachTitle(coach.getOwnerTitle())
                .customerName(customerName).topic(trimToNull(req.topic()))
                .leadId(linkedLead == null ? null : linkedLead.getId())
                .transcript(transcript).transcriptChars(transcript.length())
                .source("text")
                .shareTranscriptWithCoach(Boolean.TRUE.equals(req.shareTranscriptWithCoach()))
                .status("active").createdAt(now).updatedAt(now).build();
        sessionRepository.save(session);

        List<ReviewSegment> segments = new ArrayList<>();
        int seq = 1;
        for (ReviewSegmenter.SegmentDraft d : drafts) {
            segments.add(ReviewSegment.builder()
                    .id(UUID.randomUUID()).sessionId(session.getId()).seq(seq++)
                    .speaker(d.speaker()).text(d.text()).charCount(d.text().length())
                    .createdAt(now).build());
        }
        segmentRepository.saveAll(segments);

        audit.record("sales.review.create", "review_session", session.getId(),
                Map.of("coachSkillId", coach.getId().toString(), "segments", segments.size()));
        // 创建即入队，用户不用再点一次「生成报告」
        requestReport(session.getId());
        return detail(session.getId());
    }

    public Page<ReviewSessionView> list(int page, int size, String status, String scope) {
        UUID companyId = access.requireCompanyId();
        boolean companyScope = "company".equals(scope);
        if (companyScope && !access.canViewAll()) {
            throw new BusinessException(403, "无全公司查看权限");
        }
        Pageable p = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100));
        UUID me = TokenContext.get();
        String st = trimToNull(status);
        Page<ReviewSession> rows = companyScope
                ? (st == null
                        ? sessionRepository.findByCompanyIdOrderByCreatedAtDesc(companyId, p)
                        : sessionRepository.findByCompanyIdAndStatusOrderByCreatedAtDesc(companyId, st, p))
                : (st == null
                        ? sessionRepository.findByCompanyIdAndReviewerIdOrderByCreatedAtDesc(companyId, me, p)
                        : sessionRepository.findByCompanyIdAndReviewerIdAndStatusOrderByCreatedAtDesc(
                                companyId, me, st, p));

        List<UUID> reviewerIds = rows.getContent().stream().map(ReviewSession::getReviewerId).distinct().toList();
        Map<UUID, String> names = loadNames(reviewerIds);
        List<UUID> sessionIds = rows.getContent().stream().map(ReviewSession::getId).toList();
        Map<UUID, ReviewReportView> latestReports = latestReports(sessionIds);
        return rows.map(s -> toView(s, names.get(s.getReviewerId()), null,
                latestReports.get(s.getId())));
    }

    public Page<CoachOptionView> searchCoaches(String keyword, int page, int size) {
        UUID companyId = access.requireCompanyId();
        Pageable p = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 50));
        String kw = trimToNull(keyword);
        Page<Skill> skills = skillRepository.searchCoaches(companyId, "published", kw, p);
        // 批量取颗粒/场景统计（一次查询，避免逐分身后台查）
        List<UUID> spaceIds = skills.getContent().stream()
                .map(Skill::getSpaceId).filter(java.util.Objects::nonNull).toList();
        Map<UUID, long[]> stats = new java.util.HashMap<>();
        if (!spaceIds.isEmpty()) {
            for (Object[] row : grainRepository.activeStatsBySpaceIdIn(spaceIds)) {
                stats.put((UUID) row[0], new long[]{
                        ((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
            }
        }
        return skills.map(s -> {
            long[] st = stats.getOrDefault(s.getSpaceId(), new long[]{0, 0});
            return new CoachOptionView(s.getId(),
                    s.getDisplayName() != null ? s.getDisplayName() : s.getOwnerName(),
                    s.getOwnerName(), s.getStatus(), st[0], st[1], s.getUpdatedAt());
        });
    }

    public ReviewDetailView detail(UUID id) {
        ReviewSession s = access.requireSession(id);
        List<ReviewSegmentView> segments = segmentRepository.findBySessionIdOrderBySeqAsc(id).stream()
                .map(x -> new ReviewSegmentView(x.getId(), x.getSeq(), x.getSpeaker(), x.getText()))
                .toList();
        ReviewReport latest = reportRepository.findFirstBySessionIdOrderByVersionDesc(id).orElse(null);
        long taskCount = taskRepository.countByReviewId(id);
        ReviewSessionView view = toView(s,
                loadNames(List.of(s.getReviewerId())).get(s.getReviewerId()),
                taskCount, latest == null ? null : toReportView(latest));
        Lead lead = s.getLeadId() == null
                ? null : leadRepository.findById(s.getLeadId()).orElse(null);
        ReviewDetailView.LeadSummary leadSummary = lead == null
                ? null : new ReviewDetailView.LeadSummary(lead.getId(), lead.getName(), lead.getStatus());
        return new ReviewDetailView(view, segments, leadSummary);
    }

    /**
     * 同一客户的上一次复盘：上次的下一步完成情况 + 客户信号，用于本次对比。
     * 没有关联客户或没有更早的复盘时返回 null。
     */
    public com.aiextract.dto.PreviousReviewView previous(UUID sessionId) {
        ReviewSession current = access.requireSession(sessionId);
        if (current.getLeadId() == null) {
            return null;
        }
        Optional<ReviewSession> found = access.canViewAll()
                ? sessionRepository.findFirstByCompanyIdAndLeadIdAndIdNotAndCreatedAtBeforeOrderByCreatedAtDesc(
                        current.getCompanyId(), current.getLeadId(), sessionId, current.getCreatedAt())
                : sessionRepository
                        .findFirstByCompanyIdAndLeadIdAndReviewerIdAndIdNotAndCreatedAtBeforeOrderByCreatedAtDesc(
                                current.getCompanyId(), current.getLeadId(), current.getReviewerId(),
                                sessionId, current.getCreatedAt());
        ReviewSession prev = found.orElse(null);
        if (prev == null) {
            return null;
        }
        ReviewReport report = reportRepository.findFirstBySessionIdOrderByVersionDesc(prev.getId()).orElse(null);
        com.fasterxml.jackson.databind.JsonNode signals = null;
        if (report != null && report.getPayload() != null && !report.getPayload().isBlank()) {
            try {
                signals = objectMapper.readTree(report.getPayload()).path("customerSignals");
            } catch (Exception e) {
                log.warn("上次复盘 payload 解析失败 sessionId={}", prev.getId(), e);
            }
        }
        List<com.aiextract.dto.PreviousReviewView.PreviousTaskView> tasks = taskRepository
                .findByReviewIdOrderByCreatedAtAsc(prev.getId()).stream()
                .map(t -> new com.aiextract.dto.PreviousReviewView.PreviousTaskView(
                        t.getId(), t.getTitle(), t.getChannel(), t.getDueAt(),
                        t.getStatus(), t.getCompletedAt()))
                .toList();
        return new com.aiextract.dto.PreviousReviewView(prev.getId(), prev.getCreatedAt(),
                prev.getCustomerName(), prev.getTopic(),
                report == null ? null : report.getSummary(), signals, tasks);
    }

    @Transactional
    public UUID requestReport(UUID sessionId) {
        // 先锁会话行：双击/并发请求串行化，避免两个 queued 抢同一 version
        sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(404, "复盘不存在或不可访问"));
        ReviewSession session = access.requireSession(sessionId);
        if ("closed".equals(session.getStatus())) {
            throw new BusinessException(409, "该复盘已关闭，不能重新生成报告");
        }
        Optional<ReviewReport> active = reportRepository
                .findFirstBySessionIdAndStatusInOrderByVersionDesc(
                        sessionId, List.of("queued", "generating"));
        if (active.isPresent()) {
            // 租约已耗尽：先把卡死的 job 置 failed，再允许生成新版本
            if (!reportService.expireIfStale(active.get())) {
                return active.get().getId();
            }
        }
        int version = reportRepository.findMaxVersion(sessionId) + 1;
        ReviewReport report = ReviewReport.builder()
                .id(UUID.randomUUID()).companyId(session.getCompanyId()).sessionId(sessionId)
                // v2：报告新增 primaryAction / infoGaps，旧报告按可选字段兼容
                .version(version).status("queued").promptVersion("v2").attempts(0)
                .createdBy(TokenContext.get())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        reportRepository.save(report);
        // 同一事务内 create() 会立刻回读 detail()，先 flush 保证能看到刚入队的版本
        reportRepository.flush();
        audit.record("sales.report.request", "review_report", report.getId(),
                Map.of("sessionId", sessionId.toString(), "version", version));
        return report.getId();
    }

    @Transactional
    public boolean updateShare(UUID sessionId, boolean share) {
        ReviewSession session = access.requireSession(sessionId);
        if (!session.getReviewerId().equals(TokenContext.get())) {
            throw new BusinessException(403, "只有复盘发起人能修改原文授权");
        }
        session.setShareTranscriptWithCoach(share);
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);
        audit.record("sales.review.share", "review_session", sessionId, Map.of("share", share));
        return share;
    }

    public ReviewReportView latestReport(UUID sessionId) {
        access.requireSession(sessionId);
        return reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId)
                .map(this::toReportView)
                .orElse(null);
    }

    public List<ReviewReportView> reports(UUID sessionId) {
        access.requireSession(sessionId);
        // 列表只回元数据，历史版本 payload 由详情页按需拉取（R22）
        return reportRepository.findBySessionIdOrderByVersionDesc(sessionId).stream()
                .map(com.aiextract.dto.ReviewReportViewMapper::toMetaView).toList();
    }

    /** 单个历史版本的完整报告（版本切换时按需拉取） */
    public ReviewReportView report(UUID sessionId, UUID reportId) {
        access.requireSession(sessionId);
        ReviewReport r = reportRepository.findById(reportId)
                .filter(x -> sessionId.equals(x.getSessionId()))
                .orElseThrow(() -> new BusinessException(404, "报告不存在"));
        return toReportView(r);
    }

    private Map<UUID, String> loadNames(List<UUID> userIds) {
        List<UUID> ids = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> out.put(u.getId(), u.getName()));
        return out;
    }

    private Map<UUID, ReviewReportView> latestReports(List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ReviewReportView> out = new HashMap<>();
        for (ReviewReport r : reportRepository.findLatestBySessionIds(sessionIds)) {
            out.put(r.getSessionId(), toReportView(r));
        }
        return out;
    }

    private ReviewSessionView toView(ReviewSession s, String reviewerName, Long taskCount,
                                     ReviewReportView latestReport) {
        return new ReviewSessionView(s.getId(), s.getReviewerId(), reviewerName,
                s.getCoachSkillId(), s.getCoachName(), s.getCoachTitle(),
                s.getCustomerName(), s.getTopic(), s.getStatus(), s.getTranscriptChars(),
                s.isShareTranscriptWithCoach(), s.getLeadId(), taskCount,
                s.getCreatedAt(), latestReport);
    }

    private ReviewReportView toReportView(ReviewReport r) {
        return com.aiextract.dto.ReviewReportViewMapper.toView(r, objectMapper);
    }

    private String normalizeText(String text) {
        if (text == null) {
            throw new BusinessException(400, "对话文本不能为空");
        }
        String t = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (t.isEmpty()) {
            throw new BusinessException(400, "对话文本不能为空");
        }
        return t;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
