package com.aiextract.service;

import com.aiextract.config.Permission;
import com.aiextract.dto.KnowledgeEntryRequest;
import com.aiextract.dto.SalesCommonIssueView;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSession;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 团队共性问题：把窗口内已生成报告的复盘按销售问题类别归并。
 *
 * <p>只做确定性归类（关键词命中数取最高的一类），不调用模型：
 * 结果可解释、可追溯到具体复盘与评价原文，且能在请求线程内完成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesCommonIssueService {

    /** 扫描上限：200 份报告，避免一次请求解析过多 payload。 */
    private static final int MAX_REPORTS = 200;
    private static final int MAX_SAMPLES = 3;
    private static final int MAX_REVIEW_REFS = 20;
    private static final int MAX_KNOWLEDGE_CHARS = 1500;

    private record Category(String key, String label, List<String> keywords) {
    }

    /**
     * 销售问题分类。顺序即平局时的优先级；关键词是召回手段，不是精确语义，
     * 因此每类只取命中次数最高的一类，避免一条判断被算进多个问题。
     */
    private static final List<Category> CATEGORIES = List.of(
            new Category("price", "预算与价格",
                    List.of("价格", "报价", "预算", "费用", "成本", "折扣", "便宜", "太贵", "付款", "省钱")),
            new Category("decision", "决策人与审批",
                    List.of("决策", "拍板", "老板", "领导", "审批", "签字", "负责人", "走流程")),
            new Category("need", "需求与痛点",
                    List.of("需求", "痛点", "场景", "目标", "期望", "价值", "解决的问题")),
            new Category("competition", "竞争与替换",
                    List.of("竞品", "对比", "替换", "友商", "现有系统", "迁移", "切换", "选型")),
            new Category("trust", "信任与顾虑",
                    List.of("信任", "关系", "案例", "口碑", "顾虑", "担心", "风险", "失败")),
            new Category("timeline", "时间与流程",
                    List.of("时间", "排期", "采购", "上线", "周期", "季度", "启动", "流程")),
            new Category("objection", "异议处理",
                    List.of("异议", "反驳", "拒绝", "犹豫", "推脱", "压价", "质疑", "顾虑")),
            new Category("advance", "推进动作",
                    List.of("下一步", "跟进", "会议", "材料", "拜访", "回访", "试用", "演示", "方案"))
    );

    private final SalesAccessService access;
    private final ReviewReportRepository reportRepository;
    private final ReviewSessionRepository sessionRepository;
    private final KnowledgeCaseService knowledgeCaseService;
    private final KnowledgeEntryRepository knowledgeRepository;
    private final SalesAuditService audit;
    private final ObjectMapper objectMapper;

    public SalesCommonIssueView commonIssues(int days) {
        UUID companyId = requireCompanyId();
        int period = clampDays(days);
        LocalDateTime since = LocalDateTime.now().minusDays(period);
        List<ReviewReport> reports = reportRepository
                .findByCompanyIdAndStatusAndGeneratedAtAfter(
                        companyId, "ready", since, PageRequest.of(0, MAX_REPORTS))
                .getContent();
        Map<UUID, ReviewSession> sessions = loadSessions(reports);

        Map<String, Accumulator> accumulators = new LinkedHashMap<>();
        for (ReviewReport report : reports) {
            JsonNode payload = parsePayload(report);
            if (payload == null) {
                continue;
            }
            ReviewSession session = sessions.get(report.getSessionId());
            Set<String> hit = new HashSet<>();
            for (String field : List.of("weaknesses", "risks")) {
                for (JsonNode point : payload.path(field)) {
                    String text = text(point, "point");
                    if (text == null) {
                        continue;
                    }
                    Category category = classify(text + " " + nullToEmpty(text(point, "suggestion")));
                    if (category == null) {
                        continue;
                    }
                    Accumulator acc = accumulators.computeIfAbsent(
                            category.key(), k -> new Accumulator(category));
                    acc.pointCount++;
                    hit.add(category.key());
                    if (acc.samples.size() < MAX_SAMPLES) {
                        acc.samples.add(new SalesCommonIssueView.Sample(
                                report.getSessionId(), displayName(session),
                                text, text(point, "suggestion")));
                    }
                }
            }
            for (String key : hit) {
                accumulators.get(key).reportIds.add(report.getSessionId());
            }
        }

        List<SalesCommonIssueView.Issue> issues = accumulators.values().stream()
                .filter(a -> !a.reportIds.isEmpty())
                .sorted(Comparator
                        .comparingInt((Accumulator a) -> a.reportIds.size()).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (Accumulator a) -> a.pointCount).reversed()))
                .map(a -> new SalesCommonIssueView.Issue(
                        a.category.key(), a.category.label(), a.reportIds.size(), a.pointCount,
                        a.samples, reviewRefs(a.reportIds, sessions)))
                .toList();
        return new SalesCommonIssueView(period, reports.size(), issues);
    }

    /**
     * 把一类共性问题沉淀为知识草稿（pending），管理员审批后可被分身检索或用于培训。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createTrainingKnowledge(String key, int days) {
        if (!access.hasAuthority(Permission.KNOWLEDGE_MANAGE)) {
            throw new BusinessException(403, "无知识库管理权限，不能生成培训主题");
        }
        UUID companyId = requireCompanyId();
        SalesCommonIssueView view = commonIssues(days);
        SalesCommonIssueView.Issue issue = view.issues().stream()
                .filter(i -> i.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "该问题类目在当前周期内没有数据"));
        String title = "培训主题：" + issue.label();
        // 同一类目重复点击不重复造草稿：直接复用待审草稿，避免知识库出现多条同名培训主题
        KnowledgeEntry existing = knowledgeRepository
                .findFirstByCompanyIdAndTitleAndStatusOrderByCreatedAtDesc(
                        companyId, title, KnowledgeEntry.STATUS_PENDING)
                .orElse(null);
        if (existing != null) {
            return Map.of("id", existing.getId().toString(),
                    "title", existing.getTitle(), "reused", true);
        }
        KnowledgeEntryRequest req = KnowledgeEntryRequest.builder()
                .title(title)
                .category(KnowledgeEntry.CATEGORY_METHODOLOGY)
                .sourceType(KnowledgeEntry.SOURCE_TYPE_TENANT)
                .content(buildContent(view, issue))
                .expireAt(LocalDateTime.now().plusYears(1))
                .source("团队复盘聚合：近 " + view.periodDays() + " 天 " + view.reportCount() + " 份复盘")
                .status(KnowledgeEntry.STATUS_PENDING)
                .build();
        KnowledgeEntry entry = knowledgeCaseService.createKnowledge(req, companyId);
        audit.record("sales.insight.knowledge", "knowledge", entry.getId(),
                Map.of("category", key, "reports", issue.reportCount()));
        return Map.of("id", entry.getId().toString(), "title", entry.getTitle(), "reused", false);
    }

    private UUID requireCompanyId() {
        UUID companyId = access.requireCompanyId();
        if (!access.canViewAll()) {
            throw new BusinessException(403, "仅企业管理员可查看团队共性问题");
        }
        return companyId;
    }

    private int clampDays(int days) {
        return Math.max(7, Math.min(days <= 0 ? 90 : days, 365));
    }

    private Map<UUID, ReviewSession> loadSessions(List<ReviewReport> reports) {
        List<UUID> ids = reports.stream().map(ReviewReport::getSessionId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ReviewSession> out = new HashMap<>();
        sessionRepository.findAllById(ids).forEach(s -> out.put(s.getId(), s));
        return out;
    }

    private List<SalesCommonIssueView.ReviewRef> reviewRefs(
            Set<UUID> sessionIds, Map<UUID, ReviewSession> sessions) {
        return sessionIds.stream()
                .map(sessions::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ReviewSession::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(MAX_REVIEW_REFS)
                .map(s -> new SalesCommonIssueView.ReviewRef(
                        s.getId(), displayName(s), s.getCreatedAt()))
                .toList();
    }

    private JsonNode parsePayload(ReviewReport report) {
        if (report.getPayload() == null || report.getPayload().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(report.getPayload());
        } catch (Exception e) {
            log.warn("共性问题聚合跳过损坏报告 reportId={}", report.getId());
            return null;
        }
    }

    private Category classify(String text) {
        Category best = null;
        int bestScore = 0;
        for (Category category : CATEGORIES) {
            int score = 0;
            for (String keyword : category.keywords()) {
                score += countOccurrences(text, keyword);
            }
            if (score > bestScore) {
                bestScore = score;
                best = category;
            }
        }
        return best;
    }

    private int countOccurrences(String text, String keyword) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) >= 0) {
            count++;
            index += keyword.length();
        }
        return count;
    }

    private String buildContent(SalesCommonIssueView view, SalesCommonIssueView.Issue issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 共性问题：").append(issue.label()).append('\n');
        sb.append("近 ").append(view.periodDays()).append(" 天的 ").append(view.reportCount())
                .append(" 份已生成报告的复盘中，有 ").append(issue.reportCount())
                .append(" 份出现该问题，共 ").append(issue.pointCount()).append(" 条相关判断。\n\n");
        if (!issue.samples().isEmpty()) {
            sb.append("## 典型表现\n");
            int index = 1;
            for (SalesCommonIssueView.Sample sample : issue.samples()) {
                sb.append(index++).append(". ").append(sample.point()).append('\n');
                if (sample.suggestion() != null && !sample.suggestion().isBlank()) {
                    sb.append("   - 复盘建议：").append(sample.suggestion()).append('\n');
                }
            }
            sb.append('\n');
        }
        if (!issue.reviews().isEmpty()) {
            sb.append("## 涉及复盘\n");
            for (SalesCommonIssueView.ReviewRef ref : issue.reviews().stream().limit(5).toList()) {
                sb.append("- ").append(ref.customerName() == null ? "未命名客户" : ref.customerName());
                if (ref.createdAt() != null) {
                    sb.append("（").append(ref.createdAt().toLocalDate()).append("）");
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        sb.append("## 培训建议\n")
                .append("围绕「").append(issue.label()).append("」做一次专题演练：先回看上述复盘原话，")
                .append("再让每位销售用报告里的「下次通话先做这一件事」重写开场与追问，教练现场点评。\n");
        return truncate(sb.toString());
    }

    private String truncate(String content) {
        if (content.length() <= MAX_KNOWLEDGE_CHARS) {
            return content;
        }
        return content.substring(0, MAX_KNOWLEDGE_CHARS - 20) + "\n…（内容过长已截断）";
    }

    private String displayName(ReviewSession session) {
        if (session == null) {
            return null;
        }
        if (session.getCustomerName() != null && !session.getCustomerName().isBlank()) {
            return session.getCustomerName();
        }
        return session.getTopic() != null && !session.getTopic().isBlank()
                ? session.getTopic() : "未命名客户";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class Accumulator {
        private final Category category;
        private final Set<UUID> reportIds = new HashSet<>();
        private final List<SalesCommonIssueView.Sample> samples = new ArrayList<>();
        private int pointCount;

        private Accumulator(Category category) {
            this.category = category;
        }
    }
}
