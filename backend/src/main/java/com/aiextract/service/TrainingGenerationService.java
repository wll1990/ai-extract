package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.PromptLoader;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.Skill;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.TrainingPlanVersion;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.TrainingPlanRepository;
import com.aiextract.repository.TrainingPlanVersionRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI Native 培训内容生成 —— 场景微练与个性化计划。
 *
 * <p>边界：</p>
 * <ul>
 *   <li>AI 只生成**草稿**；计划仍需人工预览、发布，学员才可见。</li>
 *   <li>AI 只能引用候选资源里的 ID，生成后逐条校验企业归属，跨租户/不存在的 ID 直接丢弃。</li>
 *   <li>所有 AI 调用在事务外完成；落库走短事务，避免长时间占用连接。</li>
 * </ul>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingGenerationService {

    private static final int MAX_PLAN_TASKS = 12;
    private static final int MAX_DRILL_TASKS = 4;
    private static final int MATERIAL_MAX_CHARS = 4000;
    private static final String DRILL_PROMPT = "training_drill_generate.md";
    private static final String PLAN_PROMPT = "training_plan_generate.md";

    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final SkillRepository skillRepository;
    private final TrainingPlanRepository planRepository;
    private final TrainingPlanVersionRepository versionRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingAccessService accessService;
    private final CompanyScopeService companyScopeService;
    private final ObjectProvider<TrainingGenerationService> selfProvider;

    /** 生成计划结果：计划 + 任务 + 生成依据（前端直接展示，不立即跳转） */
    public record GeneratedPlan(TrainingPlan plan, List<TrainingTask> tasks, String note,
                                List<String> warnings) {
    }

    /** 待落库的任务（AI 输出校验后的内部结构） */
    private record GenTask(int phaseNo, String phaseName, String type, String title,
                           String description, boolean required, String resourceType,
                           UUID resourceId, String sceneTag, String drillScenario,
                           String reason) {
    }

    // ==================== 场景微练 ====================

    /**
     * 为一个资源生成场景卡（不落库）。
     *
     * @return 规范化后的 JSON 字符串，可直接作为 drillScenario 提交
     */
    public String generateDrill(UUID resourceId, String resourceType, String sceneTag,
                                int questionCount) {
        Material material = material(resourceId, resourceType);
        int count = Math.max(1, Math.min(questionCount <= 0 ? 2 : questionCount, 3));
        Map<String, String> vars = new HashMap<>();
        vars.put("resource_type", resourceType);
        vars.put("resource_title", material.title());
        vars.put("scene_tag", sceneTag == null || sceneTag.isBlank() ? "（未指定）" : sceneTag);
        vars.put("material", truncate(material.text(), MATERIAL_MAX_CHARS));
        vars.put("question_count", String.valueOf(count));
        String raw = chatStreamAdapter.chat(promptLoader.format(DRILL_PROMPT, vars));
        JsonNode card = parseJsonObject(raw, "微练生成");
        validateDrillCard(card);
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            throw new BusinessException(500, "微练内容序列化失败");
        }
    }

    // ==================== 计划生成 ====================

    /**
     * 按目标生成个性化培训计划草稿。
     *
     * <p>候选资源来自本企业（知识/案例含公共库、分身仅本企业已发布），
     * AI 只做选择与排序；任何不在候选集里的资源 ID 都会被丢弃。</p>
     */
    public GeneratedPlan generatePlan(String goal, int phaseCount) {
        if (goal == null || goal.trim().length() < 5) {
            throw new BusinessException(400, "训练目标至少 5 个字（如：新人 30 天能独立处理价格异议）");
        }
        int phases = Math.max(3, Math.min(phaseCount <= 0 ? 4 : phaseCount, 6));
        UUID companyId = accessService.requireCompanyId();

        List<Candidate> candidates = loadCandidates(companyId);
        if (candidates.isEmpty()) {
            throw new BusinessException(409, "本企业还没有可用知识/案例/分身，请先沉淀内容再生成计划");
        }
        Map<String, Candidate> byId = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            byId.put(c.id().toString(), c);
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("goal", goal.trim());
        vars.put("phase_count", String.valueOf(phases));
        vars.put("candidates", renderCandidates(candidates));
        String raw = chatStreamAdapter.chat(promptLoader.format(PLAN_PROMPT, vars));
        JsonNode root = parseJsonObject(raw, "计划生成");

        List<String> warnings = new ArrayList<>();
        List<GenTask> tasks = new ArrayList<>();
        Set<String> usedResources = new HashSet<>();
        JsonNode arr = root.path("tasks");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new BusinessException(502, "AI 没有生成可用任务，请调整目标后重试");
        }
        for (JsonNode node : arr) {
            if (tasks.size() >= MAX_PLAN_TASKS) {
                warnings.add("任务超过 " + MAX_PLAN_TASKS + " 个，超出部分已忽略");
                break;
            }
            GenTask task = toGenTask(node, byId, usedResources, warnings);
            if (task != null) {
                tasks.add(task);
            }
        }
        if (tasks.isEmpty()) {
            throw new BusinessException(502, "AI 生成的任务未通过资源校验，请补充内容后重试");
        }

        // 先学后练：保证 drill 依据的资源此前出现过，否则降级为阅读
        tasks = enforceLearningOrder(tasks, warnings);

        // 微练场景卡逐个生成（AI 调用在事务外）；失败则降级为阅读，不伪造题目
        int drills = 0;
        List<GenTask> withCards = new ArrayList<>(tasks.size());
        for (GenTask task : tasks) {
            if (!TrainingTask.TYPE_DRILL.equals(task.type())) {
                withCards.add(task);
                continue;
            }
            if (drills >= MAX_DRILL_TASKS) {
                GenTask fallback = fallbackForFailedDrill(task, warnings,
                        "微练数量超过 " + MAX_DRILL_TASKS + " 个");
                if (fallback != null) {
                    withCards.add(fallback);
                }
                continue;
            }
            try {
                String card = generateDrill(task.resourceId(), task.resourceType(),
                        task.sceneTag(), 2);
                withCards.add(new GenTask(task.phaseNo(), task.phaseName(), task.type(),
                        task.title(), task.description(), task.required(), task.resourceType(),
                        task.resourceId(), task.sceneTag(), card, task.reason()));
                drills++;
            } catch (Exception e) {
                log.warn("计划内微练生成失败 task={} reason={}", task.title(), e.getMessage());
                GenTask fallback = fallbackForFailedDrill(task, warnings,
                        "微练生成失败：" + e.getMessage());
                if (fallback != null) {
                    withCards.add(fallback);
                }
            }
        }

        String name = text(root, "name");
        String description = text(root, "description");
        String note = text(root, "note");
        TrainingPlan plan = selfProvider.getObject().saveGeneratedPlan(
                name == null || name.isBlank() ? "AI 生成培训计划" : name,
                description, goal.trim(), note, withCards);
        List<TrainingTask> saved = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(plan.getId(), 1);
        return new GeneratedPlan(plan, saved, note, warnings);
    }

    /**
     * 短事务落库：计划 + v1 草稿版本 + 任务。
     *
     * <p>落库前再次按企业归属校验资源，AI 输出不可信；跨企业资源直接丢弃。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public TrainingPlan saveGeneratedPlan(String name, String description, String goal,
                                          String note, List<GenTask> tasks) {
        UUID companyId = accessService.requireCompanyId();
        UUID operatorId = TokenContext.get();
        LocalDateTime now = LocalDateTime.now();
        TrainingPlan plan = TrainingPlan.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .name(truncate(name, 255))
                .description(description)
                .goal(goal)
                .generatedByAi(true)
                .generationNote(note)
                .status(TrainingPlan.STATUS_DRAFT)
                .currentVersion(1)
                .createdBy(operatorId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        planRepository.save(plan);

        int order = 1;
        int phase = 0;
        for (GenTask task : tasks) {
            if (task.phaseNo() != phase) {
                phase = task.phaseNo();
                order = 1;
            }
            assertResourceUsable(task.resourceId(), task.resourceType());
            taskRepository.save(TrainingTask.builder()
                    .id(UUID.randomUUID())
                    .planId(plan.getId())
                    .version(1)
                    .phaseNo(Math.max(1, task.phaseNo()))
                    .phaseName(truncate(task.phaseName(), 100))
                    .type(task.type())
                    .title(truncate(task.title() == null || task.title().isBlank()
                            ? "训练任务" : task.title(), 255))
                    .description(task.description())
                    .isRequired(task.required())
                    .orderNo(order++)
                    .prerequisites(TrainingTask.EMPTY_PREREQUISITES)
                    .resourceType(task.resourceType())
                    .resourceId(task.resourceId())
                    .sceneTag(truncate(task.sceneTag(), 50))
                    .allowHints(!TrainingTask.TYPE_DRILL.equals(task.type()))
                    .passCriteria(TrainingTask.TYPE_DRILL.equals(task.type())
                            ? "{\"minScore\":60,\"minPointCoverage\":0.6}" : null)
                    .maxAttempts(TrainingTask.TYPE_DRILL.equals(task.type()) ? 2 : 1)
                    .deadlinePolicy(TrainingTask.DEADLINE_AUTO_SUBMIT)
                    .resourceSnapshot(TrainingTask.TYPE_DRILL.equals(task.type())
                            ? task.drillScenario() : null)
                    .snapshotVersion(TrainingTask.TYPE_DRILL.equals(task.type()) ? 1 : null)
                    .snapshotAt(TrainingTask.TYPE_DRILL.equals(task.type()) ? now : null)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        versionRepository.save(TrainingPlanVersion.builder()
                .id(UUID.randomUUID())
                .planId(plan.getId())
                .version(1)
                .status(TrainingPlanVersion.STATUS_DRAFT)
                .taskCount(tasks.size())
                .createdBy(operatorId)
                .createdAt(now)
                .build());
        return plan;
    }

    // ==================== 候选与校验 ====================

    private record Candidate(UUID id, String type, String title, String subtitle) {
    }

    private record Material(String title, String text) {
    }

    private List<Candidate> loadCandidates(UUID companyId) {
        List<Candidate> candidates = new ArrayList<>();
        knowledgeRepository.search(false, companyId, null, true, null, null,
                        KnowledgeEntry.STATUS_ACTIVE, null, false, false, false,
                        PageRequest.of(0, 30))
                .forEach(k -> candidates.add(new Candidate(k.getId(), TrainingTask.RESOURCE_KNOWLEDGE,
                        k.getTitle(), k.getCategory())));
        caseRepository.search(false, companyId, null, true, null, null, null,
                        IndustryCase.STATUS_ACTIVE, null, false, false, false,
                        PageRequest.of(0, 30))
                .forEach(c -> candidates.add(new Candidate(c.getId(), TrainingTask.RESOURCE_CASE,
                        c.getTitle(), c.getSceneTag())));
        skillRepository.findByCompanyIdAndStatus(companyId, "published").stream().limit(10)
                .forEach(s -> candidates.add(new Candidate(s.getId(), TrainingTask.RESOURCE_SKILL,
                        s.getDisplayName(), "分身陪练")));
        return candidates;
    }

    private String renderCandidates(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Candidate c : candidates) {
            sb.append("- id=").append(c.id())
                    .append(" | type=").append(c.type())
                    .append(" | title=").append(c.title())
                    .append(" | tag=").append(c.subtitle() == null ? "" : c.subtitle())
                    .append('\n');
        }
        return sb.toString();
    }

    private Material material(UUID resourceId, String resourceType) {
        if (TrainingTask.RESOURCE_KNOWLEDGE.equals(resourceType)) {
            KnowledgeEntry k = knowledgeRepository.findById(resourceId)
                    .orElseThrow(() -> new BusinessException(404, "知识不存在或不可访问"));
            companyScopeService.assertKnowledgeReadable(resourceId);
            return new Material(k.getTitle(), nvl(k.getContent()));
        }
        if (TrainingTask.RESOURCE_CASE.equals(resourceType)) {
            IndustryCase c = caseRepository.findById(resourceId)
                    .orElseThrow(() -> new BusinessException(404, "案例不存在或不可访问"));
            companyScopeService.assertCaseReadable(resourceId);
            String text = String.join("\n",
                    nvl(c.getCustomerProfile()), nvl(c.getObjection()), nvl(c.getEmotionChange()),
                    nvl(c.getKeyActions()), nvl(c.getScripts()), nvl(c.getReusablePoints()),
                    nvl(c.getFailurePoints()));
            return new Material(c.getTitle(), text);
        }
        if (TrainingTask.RESOURCE_SKILL.equals(resourceType)) {
            Skill s = skillRepository.findById(resourceId)
                    .orElseThrow(() -> new BusinessException(404, "分身不存在或不可访问"));
            companyScopeService.assertSkillOwnership(resourceId);
            return new Material(s.getDisplayName(), nvl(s.getIntroProfile())
                    + "\n" + nvl(s.getOpeningMessage()));
        }
        throw new BusinessException(400, "不支持的资源类型：" + resourceType);
    }

    private void assertResourceUsable(UUID resourceId, String resourceType) {
        if (TrainingTask.RESOURCE_KNOWLEDGE.equals(resourceType)) {
            companyScopeService.assertKnowledgeReadable(resourceId);
        } else if (TrainingTask.RESOURCE_CASE.equals(resourceType)) {
            companyScopeService.assertCaseReadable(resourceId);
        } else if (TrainingTask.RESOURCE_SKILL.equals(resourceType)) {
            companyScopeService.assertSkillOwnership(resourceId);
        } else {
            throw new BusinessException(400, "不支持的任务资源类型：" + resourceType);
        }
    }

    private GenTask toGenTask(JsonNode node, Map<String, Candidate> byId,
                              Set<String> usedResources, List<String> warnings) {
        String type = node.path("type").asText("");
        if (!Set.of(TrainingTask.TYPE_READING, TrainingTask.TYPE_DRILL,
                TrainingTask.TYPE_PRACTICE).contains(type)) {
            warnings.add("忽略不支持的任务类型：" + type);
            return null;
        }
        String resourceIdText = node.path("resourceId").asText("");
        Candidate candidate = byId.get(resourceIdText);
        if (candidate == null) {
            warnings.add("忽略不在候选内的资源：" + resourceIdText);
            return null;
        }
        if (!usedResources.add(resourceIdText)) {
            warnings.add("忽略重复使用的资源：" + candidate.title());
            return null;
        }
        if (TrainingTask.TYPE_PRACTICE.equals(type)
                && !TrainingTask.RESOURCE_SKILL.equals(candidate.type())) {
            warnings.add("陪练任务只能绑定分身：" + candidate.title());
            return null;
        }
        if (!TrainingTask.TYPE_PRACTICE.equals(type)
                && TrainingTask.RESOURCE_SKILL.equals(candidate.type())) {
            warnings.add("分身资源只能用于陪练：" + candidate.title());
            return null;
        }
        String title = node.path("title").asText("");
        return new GenTask(
                Math.max(1, node.path("phaseNo").asInt(1)),
                node.path("phaseName").asText("阶段"),
                type,
                title.isBlank() ? candidate.title() : title,
                node.path("description").asText(""),
                node.path("isRequired").asBoolean(true),
                candidate.type(),
                candidate.id(),
                node.path("sceneTag").asText(candidate.subtitle()),
                null,
                node.path("reason").asText(""));
    }

    /** 先学后练：drill 没有可阅读的依据资源时降级/丢弃，避免「没学就考」 */
    private List<GenTask> enforceLearningOrder(List<GenTask> tasks, List<String> warnings) {
        List<GenTask> result = new ArrayList<>(tasks.size());
        for (GenTask task : tasks) {
            if (TrainingTask.TYPE_DRILL.equals(task.type())
                    && !TrainingTask.RESOURCE_KNOWLEDGE.equals(task.resourceType())
                    && !TrainingTask.RESOURCE_CASE.equals(task.resourceType())) {
                GenTask fallback = fallbackForFailedDrill(task, warnings, "微练缺少可阅读的依据资源");
                if (fallback != null) {
                    result.add(fallback);
                }
                continue;
            }
            result.add(task);
        }
        return result;
    }

    /**
     * 微练不可用时的退路：知识/案例可降级为阅读；分身没有阅读形态，只能丢弃。
     * 不降级会写出「reading + skill」这种违反数据库约束的任务。
     */
    private GenTask fallbackForFailedDrill(GenTask task, List<String> warnings, String reason) {
        if (!TrainingTask.RESOURCE_KNOWLEDGE.equals(task.resourceType())
                && !TrainingTask.RESOURCE_CASE.equals(task.resourceType())) {
            warnings.add("任务「" + task.title() + "」已丢弃：" + reason);
            return null;
        }
        warnings.add("任务「" + task.title() + "」降级为阅读：" + reason);
        return new GenTask(task.phaseNo(), task.phaseName(), TrainingTask.TYPE_READING,
                task.title(), task.description(), task.required(), task.resourceType(),
                task.resourceId(), task.sceneTag(), null, task.reason());
    }

    // ==================== 解析 ====================

    private JsonNode parseJsonObject(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(502, label + "返回为空，请重试");
        }
        String cleaned = raw.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "");
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (node == null || !node.isObject()) {
                throw new BusinessException(502, label + "返回的不是 JSON 对象");
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, label + "返回无法解析：" + e.getMessage());
        }
    }

    private void validateDrillCard(JsonNode card) {
        if (card.path("scenario").asText("").isBlank()) {
            throw new BusinessException(502, "微练缺少场景描述，请重试");
        }
        JsonNode questions = card.get("questions");
        if (questions == null || !questions.isArray()
                || questions.isEmpty() || questions.size() > 3) {
            throw new BusinessException(502, "微练问题数量不合法（1-3 个）");
        }
        for (JsonNode q : questions) {
            if (q.path("question").asText("").isBlank()) {
                throw new BusinessException(502, "微练问题为空");
            }
            JsonNode points = q.get("expectedPoints");
            if (points == null || !points.isArray() || points.isEmpty()) {
                throw new BusinessException(502, "微练问题缺少评分要点");
            }
        }
        // 规范化：补齐可选字段，前端不用判空
        ObjectNode root = (ObjectNode) card;
        if (!root.hasNonNull("clientRole")) {
            root.put("clientRole", "");
        }
        ArrayNode fixed = objectMapper.createArrayNode();
        for (JsonNode q : questions) {
            ObjectNode item = fixed.addObject();
            item.put("question", q.path("question").asText(""));
            item.put("context", q.path("context").asText(""));
            item.set("expectedPoints", q.path("expectedPoints"));
            item.put("scoringRule", q.path("scoringRule").asText(""));
        }
        root.set("questions", fixed);
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
