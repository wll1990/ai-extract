package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 内容体检 —— 知识/案例的可上线质量与维护建议。
 *
 * <p>全部是确定性检查（不调用模型）：重复、过期、不可检索、字段缺失、结果可疑。
 * 返回结构化 issues 与相似条目，前端展示「修什么、为什么」。</p>
 */
@Service
@RequiredArgsConstructor
public class ContentHealthService {

    private static final int MIN_KNOWLEDGE_CHARS = 80;
    private static final int EXPIRE_WARN_DAYS = 30;

    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final CompanyScopeService companyScopeService;

    public record Issue(String code, String severity, String message, String suggestion) {
    }

    public record Similar(UUID id, String title, String status, String hint) {
    }

    public record HealthView(UUID id, String type, String title, String status,
                             List<Issue> issues, List<Similar> similar) {
    }

    public HealthView knowledgeHealth(UUID id) {
        KnowledgeEntry k = companyScopeService.requireKnowledgeReadable(id);
        List<Issue> issues = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        if (k.getTitle() == null || k.getTitle().isBlank()) {
            issues.add(new Issue("NO_TITLE", "high", "缺少标题", "标题要能被搜索到"));
        }
        if (k.getContent() == null || k.getContent().length() < MIN_KNOWLEDGE_CHARS) {
            issues.add(new Issue("TOO_SHORT", "high",
                    "正文过短（<" + MIN_KNOWLEDGE_CHARS + " 字）",
                    "补充结论、适用场景与可执行动作"));
        }
        if (k.getSource() == null || k.getSource().isBlank()) {
            issues.add(new Issue("NO_SOURCE", "medium", "缺少出处", "补文档/书籍/对话来源，便于追溯"));
        }
        if (KnowledgeEntry.STATUS_ACTIVE.equals(k.getStatus())
                && k.getEmbeddingStatus() != null && !"indexed".equals(k.getEmbeddingStatus())) {
            issues.add(new Issue("NOT_SEARCHABLE", "high",
                    "向量化未完成（" + k.getEmbeddingStatus() + "）",
                    "等待重试或手动重建索引，否则分身检索不到"));
        }
        if (k.getExpireAt() != null) {
            if (k.getExpireAt().isBefore(now)) {
                issues.add(new Issue("EXPIRED", "high", "已过期",
                        "更新内容或延期；过期条目不应继续被引用"));
            } else if (k.getExpireAt().isBefore(now.plusDays(EXPIRE_WARN_DAYS))) {
                issues.add(new Issue("EXPIRING", "medium",
                        EXPIRE_WARN_DAYS + " 天内过期", "提前复核内容是否仍然有效"));
            }
        }
        List<KnowledgeEntry> duplicates = k.getTitle() == null ? List.of()
                : knowledgeRepository.findActiveByCompanyAndLowerTitle(
                        k.getCompanyId(), k.getTitle().toLowerCase());
        duplicates.stream().filter(d -> !d.getId().equals(k.getId())).findFirst()
                .ifPresent(d -> issues.add(new Issue("DUPLICATE_TITLE", "high",
                        "同标题条目已存在：" + d.getTitle(), "合并内容或改用更具体的标题")));

        List<Similar> similar = k.getCategory() == null ? List.of()
                : knowledgeRepository.findSimilarByCategory(k.getCompanyId(), k.getCategory(),
                        k.getId(), PageRequest.of(0, 3)).stream()
                .map(s -> new Similar(s.getId(), s.getTitle(), s.getStatus(),
                        "同分类「" + s.getCategory() + "」：检查口径是否冲突"))
                .toList();
        return new HealthView(k.getId(), "knowledge", k.getTitle(), k.getStatus(), issues, similar);
    }

    public HealthView caseHealth(UUID id) {
        IndustryCase c = companyScopeService.requireCaseReadable(id);
        List<Issue> issues = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        if (!IndustryCase.isValidResult(c.getResult())) {
            issues.add(new Issue("NO_RESULT", "high", "结果未设置或非法",
                    "必须明确 win/loss/delay，否则案例无法用于训练与复盘"));
        }
        if (c.getSceneTag() == null || c.getSceneTag().isBlank()) {
            issues.add(new Issue("NO_SCENE", "medium", "缺少场景标签",
                    "补场景标签后才能被相似推荐和训练选中"));
        }
        if (c.getObjection() == null || c.getObjection().isBlank()) {
            issues.add(new Issue("NO_OBJECTION", "medium", "缺少客户异议", "案例的核心是冲突与处理"));
        }
        if (c.getKeyActions() == null || c.getKeyActions().isBlank()) {
            issues.add(new Issue("NO_ACTIONS", "high", "缺少关键动作", "补可复制的动作序列"));
        }
        if (c.getScripts() == null || c.getScripts().isBlank()) {
            issues.add(new Issue("NO_SCRIPTS", "medium", "缺少话术", "补客户原话/应对话术"));
        }
        if (IndustryCase.RESULT_WIN.equals(c.getResult())
                && (c.getReusablePoints() == null || c.getReusablePoints().isBlank())) {
            issues.add(new Issue("NO_REUSE", "high", "赢单缺少可复用点", "写清下次能直接复用什么"));
        }
        if (IndustryCase.RESULT_LOSS.equals(c.getResult())
                && (c.getFailurePoints() == null || c.getFailurePoints().isBlank())) {
            issues.add(new Issue("NO_FAILURE", "high", "丢单缺少失败点", "写清哪一步做错，避免重复"));
        }
        if (c.getExpireAt() != null) {
            if (c.getExpireAt().isBefore(now)) {
                issues.add(new Issue("EXPIRED", "high", "已过期", "产品/政策变化后案例可能失效"));
            } else if (c.getExpireAt().isBefore(now.plusDays(EXPIRE_WARN_DAYS))) {
                issues.add(new Issue("EXPIRING", "medium", EXPIRE_WARN_DAYS + " 天内过期",
                        "提前复核案例是否仍然成立"));
            }
        }
        List<IndustryCase> duplicates = c.getTitle() == null ? List.of()
                : caseRepository.findActiveByCompanyAndLowerTitle(
                        c.getCompanyId(), c.getTitle().toLowerCase());
        duplicates.stream().filter(d -> !d.getId().equals(c.getId())).findFirst()
                .ifPresent(d -> issues.add(new Issue("DUPLICATE_TITLE", "high",
                        "同标题案例已存在：" + d.getTitle(), "合并或改用更具体的标题")));

        List<Similar> similar = c.getSceneTag() == null ? List.of()
                : caseRepository.findSimilarByScene(c.getCompanyId(), c.getSceneTag(), c.getId(),
                        PageRequest.of(0, 3)).stream()
                .map(s -> new Similar(s.getId(), s.getTitle(), s.getStatus(),
                        IndustryCase.RESULT_WIN.equals(s.getResult()) ? "同场景赢单对照"
                                : IndustryCase.RESULT_LOSS.equals(s.getResult()) ? "同场景丢单对照"
                                : "同场景案例"))
                .toList();
        return new HealthView(c.getId(), "case", c.getTitle(), c.getStatus(), issues, similar);
    }
}
