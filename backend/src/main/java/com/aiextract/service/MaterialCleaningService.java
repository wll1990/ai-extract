package com.aiextract.service;

import com.aiextract.config.DomainConfig;
import com.aiextract.config.PromptLoader;
import com.aiextract.model.ExtractionDropLog;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillMaterial;
import com.aiextract.model.Space;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.ExtractionDropLogRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 素材清洗管道编排层
 *
 * <p>
 * 规则引擎（三层清洗） + AI引擎（语义提取 + 场景归类）。
 * 核心设计原则：规则做脏活（0幻觉风险），AI做智力活（提取可执行技能颗粒）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialCleaningService {
    private static final String KEY_APPLICABLE_CONDITION = "applicable_condition";
    private static final String KEY_BACKGROUND = "background";
    private static final String KEY_BUYER_PERSONA = "buyer_persona";
    private static final String KEY_BUYING_STAGE = "buying_stage";
    private static final String KEY_COMMON_MISTAKES = "common_mistakes";
    private static final String KEY_COMMON_PHRASES = "commonPhrases";
    private static final String KEY_COMMUNICATION_PREFERENCES = "communicationPreferences";
    private static final String KEY_COMPETITIVE_CONTEXT = "competitive_context";
    private static final String KEY_COMPOSITE = "composite";
    private static final String KEY_CORE_HABITS = "core_habits";
    private static final String KEY_DEAL_SIZE_HINT = "deal_size_hint";
    private static final String KEY_DIFFERENTIATORS = "differentiators";
    private static final String KEY_EXPERT_THOUGHT = "expert_thought";
    private static final String KEY_INDEX = "index";
    private static final String KEY_INDUSTRY_SIGNALS = "industry_signals";
    private static final String KEY_KNOWLEDGE_DOMAINS = "knowledgeDomains";
    private static final String KEY_METHODOLOGY_NAME = "methodology_name";
    private static final String KEY_NEEDS_MANUAL = "needs_manual";
    private static final String KEY_ONELINER = "oneliner";
    private static final String KEY_PASSED = "passed";
    private static final String KEY_PERSONALITY = "personality";
    private static final String KEY_RESULTS = "results";
    private static final String KEY_SCENE_DESCRIPTION = "scene_description";
    private static final String KEY_SCORES = "scores";
    private static final String KEY_SPEAKING_STYLE = "speakingStyle";
    private static final String KEY_STANDARD_SCRIPT = "standard_script";
    private static final String KEY_TAG = "tag";
    private static final String KEY_TEXT = "text";
    private static final String KEY_VERDICT = "verdict";

    // 自注入代理：AI/HTTP 调用在事务外，DB 状态更新走短事务
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private MaterialCleaningService self;

    private final MaterialNoiseCleaner noiseCleaner;
    private final BusinessNoiseFilter businessFilter;
    private final TextNormalizer normalizer;
    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final SkillMaterialRepository materialRepository;
    private final ExperienceGrainRepository grainRepository;
    private final SkillRepository skillRepository;
    private final SpaceRepository spaceRepository;
    private final com.aiextract.config.DomainConfigLoader domainConfigLoader;
    private final com.aiextract.repository.UserRepository userRepository;
    private final com.aiextract.repository.SkillProfileRepository profileRepository;
    private final ExtractionDropLogRepository dropLogRepository;
    private final com.aiextract.service.precheck.MaterialPreChecker preChecker;
    private final WebClient webClient;
    @Qualifier("cleanExecutor")
    private final Executor cleanExecutor;
    private final ObjectMapper objectMapper;

    /** AI 服务地址 */
    @org.springframework.beans.factory.annotation.Value("${ai.service.url}")
    private String aiServiceUrl;

    /** 文件存储路径 */
    @org.springframework.beans.factory.annotation.Value("${storage.local.path}")
    private String storageBasePath;

    // ==================== 素材上传（Controller → 本层） ====================

    /**
     * 通过空间上传素材 — 自动解析 spaceId → skillId
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> uploadMaterialToSpace(MultipartFile file, UUID spaceId,
            UUID skillId, String skillName, UUID userId,
            String domain) {
        // 已有 skill 时从 skill 获取 domain（覆盖前端传的）
        if (skillId != null) {
            Skill existing = skillRepository.findById(skillId).orElse(null);
            if (existing != null && existing.getDomain() != null) {
                domain = existing.getDomain();
            }
        }

        // spaceId → 找到或创建 skill
        UUID resolvedSpaceId = null;
        if (spaceId != null && skillId == null) {
            Skill existingSkill = skillRepository.findBySpaceId(spaceId).orElse(null);
            if (existingSkill != null) {
                skillId = existingSkill.getId();
                if (existingSkill.getDomain()

                        != null) {
                    domain = existingSkill.getDomain();

                }
            } else {
                Space sp = spaceRepository.findById(spaceId).orElse(null);
                skillName = sp != null ? sp.getTitle() : "未命名";
                // 保留原始 spaceId，uploadMaterial 中直接在现有空间下创建 Skill
                resolvedSpaceId = spaceId;
            }
        }
        return uploadMaterial(file, skillId, skillName, userId, resolvedSpaceId, domain);
    }

    /**
     * 上传素材并创建关联实体（Space + Skill + Material）
     *
     * <p>
     * 事务范围：DB INSERT × 3 + 文件落盘。不含 AI 调用，
     * 文件解析和萃取由 MaterialCleaningScheduler 异步执行。
     * </p>
     *
     * @param domain 领域 ID（新建分身时由前端传入，已有分身为 null 则继承）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> uploadMaterial(MultipartFile file, UUID skillId,
            String skillName, UUID userId,
            UUID existingSpaceId, String domain) {
        Skill skill;
        boolean skillCreated = false;

        if (skillId != null) {
            skill = skillRepository.findById(skillId)
                    .orElseThrow(() -> new RuntimeException("分身不存在"));
        } else if (existingSpaceId != null) {
            // 在已有空间下创建 Skill（素材上传到指定销冠空间）
            Space existingSpace = spaceRepository.findById(existingSpaceId)
                    .orElseThrow(() -> new RuntimeException("空间不存在"));
            // 检查重名
            if (skillName != null && skillRepository.findByDisplayName(skillName).isPresent()) {
                throw new RuntimeException("分身名称已存在，请选择已有分身");
            }
            // 从空间所属用户自动填充 ownerName / ownerTitle
            String autoOwnerName = null;
            String autoOwnerTitle = existingSpace.getDescription();
            if (existingSpace.getUserId() != null) {
                autoOwnerName = userRepository.findById(existingSpace.getUserId())
                        .map(com.aiextract.model.User::getName).orElse(null);
            }
            skill = Skill.builder()
                    .id(UUID.randomUUID())
                    .spaceId(existingSpaceId)
                    .displayName(skillName)
                    .ownerName(autoOwnerName)
                    .ownerTitle(autoOwnerTitle)
                    .domain(domain)
                    .status("generating")
                    .modelName("deepseek-chat")
                    .modelConfig("{}")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            skillRepository.save(skill);
            skillCreated = true;
            log.info("在已有空间下新建分身: name={}, skillId={}, spaceId={}, ownerName={}, domain={}",
                    skillName, skill.getId(), existingSpaceId, autoOwnerName, domain);
        } else {
            // 检查重名 — 用数据库查询，不加载全部
            if (skillName != null && skillRepository.findByDisplayName(skillName).isPresent()) {
                throw new RuntimeException("分身名称已存在，请选择已有分身");
            }
            // 新建空间（管理员直接创建，不关联已有空间）
            UUID spaceId = UUID.randomUUID();
            Space space = Space.builder()
                    .id(spaceId)
                    .userId(userId)
                    .title(skillName)
                    .description(skillName + " · 资深销冠")
                    .isPublic(false)
                    .status("active")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            spaceRepository.save(space);

            skill = Skill.builder()
                    .id(UUID.randomUUID())
                    .spaceId(spaceId)
                    .displayName(skillName)
                    .domain(domain)
                    .status("generating")
                    .modelName("deepseek-chat")
                    .modelConfig("{}")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            skillRepository.save(skill);
            skillCreated = true;
            log.info("新建分身: name={}, id={}, domain={}", skillName, skill.getId(), domain);
        }

        // 保存文件
        String month = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        File dir = new File(storageBasePath + "/skills/" + skill.getId() + "/" + month);
        {
            if (!dir.exists())
                dir.mkdirs();
        }

        String originalName = file.getOriginalFilename();
        String savedName = UUID.randomUUID() + "_" + (originalName != null ? originalName : "file");
        File dest = new File(dir, savedName).getAbsoluteFile();
        try {
            file.transferTo(dest);
        } catch (Exception e) {
            log.error("文件保存失败: path={}, originalName={}, size={}",
                    dest.getAbsolutePath(), originalName, file.getSize(), e);
            throw new RuntimeException("文件保存失败: " + e.getMessage());
        }

        // 创建素材记录 — 不设 id，由 @GeneratedValue 在 persist 时生成并回填
        SkillMaterial material = SkillMaterial.builder()
                .skillId(skill.getId())
                .uploadedBy(userId)
                .fileName(originalName)
                .fileUrl(dest.toPath().normalize().toAbsolutePath().toString())
                .fileType(file.getContentType())
                .fileSize(file.getSize())
                .version(1)
                .status("uploaded")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        material = materialRepository.save(material);

        // 预检（纯文本文件即时检查，二进制文件等 parseFile 后再检）
        Map<String, Object> preCheckData = null;
        Map<String, Object> acceptanceData = new LinkedHashMap<>();
        acceptanceData.put(KEY_PASSED, true);
        try {
            String textForCheck = readTextContent(dest, file.getContentType());
            if (textForCheck != null && !textForCheck.isBlank()) {
                // 优先从 skill 取 domain（已有分身），否则用参数
                String effectiveDomain = domainConfigLoader.resolveDomain(skill);
                if (effectiveDomain == null) {

                    effectiveDomain = domain;

                }
                // Gate 1: 准入检查
                var acceptance = preChecker.checkAcceptance(textForCheck, effectiveDomain);
                acceptanceData.put(KEY_PASSED, acceptance.passed());
                if (!acceptance.passed()) {
                    acceptanceData.put("rejectCode", acceptance.rejectCode());
                    acceptanceData.put("rejectReason", acceptance.rejectReason());
                    acceptanceData.put("details", acceptance.details());
                    material.setStatus("rejected");
                    material.setAnalysisNotes(acceptance.rejectReason());
                    materialRepository.save(material);
                } else {
                    // 重复检测 — 用已有素材原文比较（主要） + 锦囊比较（辅助）
                    List<String> existingMaterialTexts = materialRepository
                            .findBySkillId(skill.getId()).stream()
                            .map(SkillMaterial::getParsedContent)
                            .filter(t -> t != null && !t.isBlank())
                            .toList();
                    var dupCheck = preChecker.checkDuplicate(textForCheck, skill.getSpaceId(),
                            effectiveDomain, existingMaterialTexts);
                    if (!dupCheck.passed()) {
                        acceptanceData.put(KEY_PASSED, false);
                        acceptanceData.put("rejectCode", dupCheck.rejectCode());
                        acceptanceData.put("rejectReason", dupCheck.rejectReason());
                        acceptanceData.put("details", dupCheck.details());
                        material.setStatus("rejected");
                        material.setAnalysisNotes(dupCheck.rejectReason());
                        materialRepository.save(material);
                    }
                }
                // Gate 2: 质量预检
                if (Boolean.TRUE.equals(acceptanceData.get(KEY_PASSED))) {
                    var quality = preChecker.evaluate(textForCheck, domain, skill.getSpaceId());
                    preCheckData = new LinkedHashMap<>();
                    preCheckData.put("overallScore", quality.overallScore());
                    preCheckData.put("grade", quality.grade());
                    preCheckData.put("estimatedGrainMin", quality.estimatedGrainMin());
                    preCheckData.put("estimatedGrainMax", quality.estimatedGrainMax());
                    preCheckData.put("detectedScenes", quality.detectedScenes());
                    List<Map<String, Object>> checks = new ArrayList<>();
                    for (var c : quality.checks()) {
                        Map<String, Object> cm = new LinkedHashMap<>();
                        cm.put("dimension", c.dimension());
                        cm.put("name", c.name());
                        cm.put(KEY_PASSED, c.passed());
                        cm.put("score", c.score());
                        cm.put("feedback", c.feedback());
                        if (c.suggestion()

                                != null) {
                            cm.put("suggestion", c.suggestion());

                        }
                        checks.add(cm);
                    }
                    preCheckData.put("checks", checks);
                }
            }
        } catch (Exception e) {
            log.warn("预检失败，跳过: {}", e.getMessage());
            acceptanceData.put(KEY_PASSED, true); // 预检异常不阻断上传
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("materialId", material.getId().toString());
        result.put("skillId", skill.getId().toString());
        result.put("skillName", skill.getDisplayName());
        result.put("skillCreated", skillCreated);
        result.put("status", material.getStatus());
        result.put("acceptance", acceptanceData);
        if (preCheckData != null) {

            result.put("preCheck", preCheckData);

        }
        return result;
    }

    /**
     * 尝试从已保存的文件中读取文本内容（仅对文本类文件有效）。
     * 二进制文件（PDF/Word/音频等）返回 null，等 parseFile 解析后再预检。
     */
    private String readTextContent(File file, String contentType) {
        if (contentType == null) {

            return null;

        }
        String ct = contentType.toLowerCase();
        // 纯文本类 — 直接读取
        if (ct.contains("text/plain") || ct.contains("text/markdown")
                || ct.contains("application/json") || ct.contains("text/csv")
                || ct.contains("text/html") || ct.contains("application/xml")) {
            try {
                return Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("读取文本内容失败: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    // ==================== 以下为清洗管线 ====================

    /**
     * 文件解析：调 AI 服务 /internal/parse-file 提取纯文本
     */
    // 无 @Transactional：HTTP 调 AI 服务不在事务内，避免长事务持连接
    public String parseFile(UUID materialId) {
        SkillMaterial material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("素材不存在: " + materialId));

        log.info("开始文件解析, materialId: {}, fileName: {}", materialId, material.getFileName());

        try {
            Map<String, String> body = Map.of(
                    "file_path", material.getFileUrl(),
                    "file_name", material.getFileName());

            Map<String, Object> response = webClient.post()
                    .uri(aiServiceUrl + "/internal/parse-file")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey(KEY_TEXT)) {
                String text = (String) response.get(KEY_TEXT);
                boolean needsManual = Boolean.TRUE.equals(response.get(KEY_NEEDS_MANUAL));

                if (needsManual) {
                    material.setStatus("uploaded");
                    material.setAnalysisNotes("图片或音频文件，需人工补充文字内容");
                    // 不设 parsedContent，避免占位文本流入 10 层萃取管线
                } else {
                    material.setParsedContent(text);
                    material.setAnalysisNotes("文件解析完成，长度: " + text.length() + "字");
                }
                materialRepository.save(material);
                log.info("文件解析完成, materialId: {}, 文本长度: {}, needsManual: {}",
                        materialId, text.length(), needsManual);
                return text;
            }
        } catch (Exception e) {
            log.error("文件解析失败, materialId: {}, fileName: {}", materialId, material.getFileName(), e);
            material.setAnalysisNotes("文件解析失败: " + e.getMessage());
            materialRepository.save(material);
        }
        return null;
    }

    // ---- 情境标注（Layer 0）----

    private ContextTags tagContext(String text, String domain) {
        try {
            String prompt = promptLoader.format("material_context_tag.md", Map.of("text",
                    sampleText(text, 3000)), domain);
            String json = chatStreamAdapter.chat(prompt);
            if (json == null) {

                return new ContextTags(null, null, null, null, null);

            }

            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
            }
            Map<String, Object> map = objectMapper.readValue(clean, Map.class);

            Object industryRaw = map.get(KEY_INDUSTRY_SIGNALS);
            String industryStr = industryRaw instanceof List<?> list
                    ? String.join(",", list.stream().map(Object::toString).toList())
                    : null;

            return new ContextTags(
                    (String) map.get(KEY_BUYING_STAGE),
                    (String) map.get(KEY_BUYER_PERSONA),
                    (String) map.get(KEY_COMPETITIVE_CONTEXT),
                    (String) map.get(KEY_DEAL_SIZE_HINT),
                    industryStr);
        } catch (Exception e) {
            log.warn("情境标注失败，使用默认值: {}", e.getMessage());
            return new ContextTags(null, null, null, null, null);
        }
    }

    // ---- 对抗验证（Layer 5）----

    /**
     * 分批对抗验证（每批最多 10 条，避免 JSON 截断）
     */
    private List<GrainCandidate> verifyGrains(List<GrainCandidate> candidates, ContextTags context,
            UUID materialId, UUID spaceId, String domain,
            List<ExtractionDropLog> drops) {
        {
            if (candidates.isEmpty())
                return candidates;
        }

        List<GrainCandidate> approved = new ArrayList<>();
        int batchSize = 10;

        for (int start = 0; start < candidates.size(); start += batchSize) {
            int end = Math.min(start + batchSize, candidates.size());
            List<GrainCandidate> batch = candidates.subList(start, end);

            // 构建批次摘要（批内 0-based 索引，消除 AI 歧义）
            StringBuilder grainBlock = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                var c = batch.get(i);
                grainBlock.append(String.format("[%d] %s\n", i, candidatePreview(c)));
            }

            try {
                String prompt = promptLoader.format("material_verify.md", Map.of(
                        "candidates_json", grainBlock.toString()), domain);
                String json = chatStreamAdapter.chat(prompt);
                if (json == null) {
                    throw new RuntimeException("AI验证响应为空, materialId=" + materialId
                            + ", batchStart=" + start + ", batchSize=" + batch.size());
                }

                String clean = json.trim();
                if (clean.startsWith("```")) {
                    clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
                }

                Map<String, Object> wrapper = objectMapper.readValue(clean, Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) wrapper.get(KEY_RESULTS);

                int batchRejected = 0;
                for (var r : results) {
                    int idx = ((Number) r.get(KEY_INDEX)).intValue();
                    {
                        if (idx < 0 || idx >= batch.size())
                            continue;
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> scores = (Map<String, Object>) r.get(KEY_SCORES);
                    double specificity = ((Number) scores.getOrDefault("specificity", 0)).doubleValue();
                    double composite = r.containsKey(KEY_COMPOSITE)
                            ? ((Number) r.get(KEY_COMPOSITE)).doubleValue()
                            : calcComposite(scores);
                    String verdict = (String) r.get(KEY_VERDICT);

                    if (specificity < 3) {
                        log.info("颗粒#{} specificity={} REJECT", start + idx, specificity);
                        drops.add(ExtractionDropLog.builder()
                                .materialId(materialId).spaceId(spaceId)
                                .stage("verification")
                                .contentPreview(candidatePreview(batch.get(idx)))
                                .detail(toJson(r))
                                .build());
                        batchRejected++;
                        continue;
                    }
                    if ("APPROVE".equals(verdict) && composite >= 3.5) {
                        var c = batch.get(idx);
                        double reproducibility = ((Number) scores.getOrDefault("reproducibility", 0)).doubleValue();
                        String difficulty = reproducibility >= 4 ? "beginner"
                                : reproducibility >= 3 ? "intermediate"
                                        : reproducibility >= 2 ? "advanced" : "master";
                        approved.add(new GrainCandidate(
                                c.sceneTag(), c.insight(), materialId,
                                composite, difficulty, toJson(r)));
                    } else {
                        log.info("颗粒#{} composite={} verdict={} REJECT", start + idx,
                                String.format("%.2f", composite), verdict);
                        drops.add(ExtractionDropLog.builder()
                                .materialId(materialId).spaceId(spaceId)
                                .stage("verification")
                                .contentPreview(candidatePreview(batch.get(idx)))
                                .detail(toJson(r))
                                .build());
                        batchRejected++;
                    }
                }
                log.info("批次验证: {}-{}, 通过:{}, 拒绝:{}", start, end - 1, batch.size() - batchRejected, batchRejected);
            } catch (Exception e) {
                log.error("批次验证异常({}-{}), materialId={}: {}", start, end - 1, materialId, e.getMessage());
                throw new RuntimeException("AI验证异常, materialId=" + materialId
                        + ", batchStart=" + start + ", batchSize=" + batch.size(), e);
            }
        }
        return approved;
    }

    /** 候选三元组单行摘要（批次 prompt 与淘汰记录共用） */
    private String candidatePreview(GrainCandidate c) {
        return String.format("场景:%s | 思考:%s | 话术:%s",
                truncate(c.insight().sceneDescription(), 80),
                truncate(c.insight().expertThought(), 80),
                truncate(c.insight().standardScript(), 80));
    }

    // ---- 同标签合并（Layer 7）----

    private List<GrainCandidate> mergeOverlapping(List<GrainCandidate> candidates, UUID materialId, String domain) {
        // ≤10 条颗粒时同标签重叠概率极低，跳过合并以节省 AI 调用
        if (candidates.size()

                <= 10) {
            return candidates;

        }

        // 按 sceneTag 分组
        Map<String, List<GrainCandidate>> grouped = new LinkedHashMap<>();
        for (var c : candidates) {
            grouped.computeIfAbsent(c.sceneTag(), k -> new ArrayList<>()).add(c);
        }

        List<GrainCandidate> merged = new ArrayList<>();
        int mergeSavings = 0;

        for (var entry : grouped.entrySet()) {
            List<GrainCandidate> group = entry.getValue();
            if (group.size() == 1) {
                merged.add(group.get(0));
                continue;
            }

            // 多条同标签 → AI 合并
            StringBuilder raw = new StringBuilder();
            for (int i = 0; i < group.size(); i++) {
                var g = group.get(i);
                raw.append(String.format("### 颗粒%d\n场景:%s\n思考:%s\n话术:%s\n错误:%s\n条件:%s\n\n",
                        i + 1,
                        g.insight().sceneDescription(),
                        g.insight().expertThought(),
                        g.insight().standardScript(),
                        g.insight().commonMistakes(),
                        g.insight().applicableCondition()));
            }

            try {
                String prompt = promptLoader.format("material_merge.md", Map.of(
                        "grains_json", raw.toString()), domain);
                String json = chatStreamAdapter.chat(prompt);
                if (json == null) {
                    merged.addAll(group);
                    continue;
                }

                String clean = json.trim();
                {
                    if (clean.startsWith("```"))
                        clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
                }

                Map<String, Object> map = objectMapper.readValue(clean, Map.class);
                double conf = ((Number) map.getOrDefault("confidence", 0.7)).doubleValue();
                if (conf < 0.6) {
                    merged.addAll(group);
                    continue;
                }

                var first = group.get(0);
                merged.add(new GrainCandidate(
                        entry.getKey(),
                        new ExtractedInsight(
                                (String) map.get(KEY_SCENE_DESCRIPTION),
                                (String) map.get(KEY_EXPERT_THOUGHT),
                                (String) map.get(KEY_STANDARD_SCRIPT),
                                (String) map.get(KEY_COMMON_MISTAKES),
                                (String) map.get(KEY_APPLICABLE_CONDITION),
                                conf),
                        materialId,
                        null, null, null)); // 合并后内容已变，旧评分失效
                mergeSavings += group.size() - 1;
            } catch (Exception e) {
                log.warn("合并失败(tag={}): {}", entry.getKey(), e.getMessage());
                merged.addAll(group);
            }
        }

        if (mergeSavings > 0) {
            log.info("同标签去重合并: {}条 → {}条 (减少{}条)", candidates.size(), merged.size(), mergeSavings);
        }
        return merged;
    }

    // ---- 模式发现（Layer 8）----

    private PatternSummary discoverPatterns(List<GrainCandidate> candidates, String domain) {
        if (candidates.size()

                < 8) {
            return null;

        }

        StringBuilder grainBlock = new StringBuilder();
        for (int i = 0; i < Math.min(candidates.size(), 20); i++) {
            var c = candidates.get(i);
            grainBlock.append(String.format("[%d] %s: %s | %s\n", i, c.sceneTag(),
                    truncate(c.insight().expertThought(), 80),
                    truncate(c.insight().standardScript(), 80)));
        }

        try {
            String prompt = promptLoader.format("material_pattern.md", Map.of(
                    "grain_block", grainBlock.toString()), domain);
            String json = chatStreamAdapter.chat(prompt);
            if (json == null) {

                return null;

            }

            String clean = json.trim();
            {
                if (clean.startsWith("```"))
                    clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
            }

            Map<String, Object> map = objectMapper.readValue(clean, Map.class);

            @SuppressWarnings("unchecked")
            List<String> habits = (List<String>) map.get(KEY_CORE_HABITS);
            @SuppressWarnings("unchecked")
            List<String> diffs = (List<String>) map.get(KEY_DIFFERENTIATORS);
            String name = (String) map.get(KEY_METHODOLOGY_NAME);
            String oneliner = (String) map.get(KEY_ONELINER);

            log.info("模式发现完成: methodology={}, habits={}, diffs={}", name,
                    habits != null ? habits.size() : 0, diffs != null ? diffs.size() : 0);
            return new PatternSummary(habits, diffs, name, oneliner);
        } catch (Exception e) {
            log.warn("模式发现失败: {}", e.getMessage());
            return null;
        }
    }

    private double calcComposite(Map<String, Object> scores) {
        double s = ((Number) scores.getOrDefault("specificity", 0)).doubleValue();
        double r = ((Number) scores.getOrDefault("reproducibility", 0)).doubleValue();
        double c = ((Number) scores.getOrDefault("causality", 0)).doubleValue();
        double d = ((Number) scores.getOrDefault("distinctiveness", 0)).doubleValue();
        double f = ((Number) scores.getOrDefault("falsifiability", 0)).doubleValue();
        return s * 0.25 + r * 0.2 + c * 0.2 + d * 0.2 + f * 0.15;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {

            return "";

        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 从长文本中均匀采样头部+中部+尾部，用固定 token 预算覆盖全文关键断面。
     * 替代 substring(0, maxLen)，避免只看开头丢失后段信息。
     */
    private String sampleText(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text != null ? text : "";
        }
        int partLen = maxLen / 3;
        int headEnd = partLen;
        int midStart = Math.max(headEnd, text.length() / 2 - partLen / 2);
        int midEnd = Math.min(midStart + partLen, text.length());
        int tailStart = Math.max(midEnd, text.length() - partLen);
        StringBuilder sb = new StringBuilder(maxLen + 3);
        sb.append(text, 0, headEnd).append('\n');
        sb.append(text, midStart, midEnd).append('\n');
        sb.append(text, tailStart, text.length());
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 短事务：更新素材状态（用于 AI 管线中多次状态切换）
     */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void updateMaterialStatus(UUID materialId, String status, String analysisNotes) {
        SkillMaterial material = materialRepository.findById(materialId).orElse(null);
        if (material == null) {

            return;

        }
        material.setStatus(status);
        if (analysisNotes != null) {

            material.setAnalysisNotes(analysisNotes);

        }
        materialRepository.save(material);
    }

    /**
     * 短事务：批量落萃取淘汰记录（排查用；调用方需自行 try/warn，不阻断主流程）
     */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void saveDropLogs(List<ExtractionDropLog> drops) {
        dropLogRepository.saveAll(drops);
    }

    /**
     * 短事务：保存清洗完成后的最终状态和元数据
     */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void finalizeMaterialCleaning(UUID materialId, String analysisNotes, String extractionMetadata) {
        SkillMaterial material = materialRepository.findById(materialId).orElse(null);
        if (material == null) {

            return;

        }
        material.setStatus("analyzed");
        if (analysisNotes != null) {

            material.setAnalysisNotes(analysisNotes);

        }
        if (extractionMetadata != null) {

            material.setExtractionMetadata(extractionMetadata);

        }
        materialRepository.save(material);
    }

    /**
     * 完整清洗管道（情境标注 → 规则清洗 → AI提取 → 场景归类 → 对抗验证）
     *
     * <p>
     * 无 @Transactional：AI 调用在事务外执行，状态更新走 self.updateMaterialStatus() 短事务
     * </p>
     */
    public List<GrainCandidate> clean(UUID materialId) {
        SkillMaterial material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("素材不存在: " + materialId));
        String rawText = material.getParsedContent();
        if (rawText == null || rawText.isBlank()) {
            throw new RuntimeException("素材无解析内容: " + materialId);
        }
        UUID spaceId = getSpaceIdBySkill(material.getSkillId());

        // ── 准入检查 + 预检（第二次机会，针对 parseFile 后的二进制文件）──
        Skill skill = skillRepository.findById(material.getSkillId()).orElse(null);
        final String domain = domainConfigLoader.resolveDomain(skill);
        try {
            // 访谈素材跳过 Gate 1 准入：转录由平台萃取师引导产生，天然在题；
            // 关键词密度等阈值面向外部上传文件挡垃圾，对口语化访谈叙事不适用
            if (!"interview".equals(material.getMaterialType())) {
                var acceptance = preChecker.checkAcceptance(rawText, domain);
                if (!acceptance.passed()) {
                    // 短事务标记 rejected，避免 detached 实例 merge 覆盖中间状态字段
                    self.updateMaterialStatus(materialId, "rejected", acceptance.rejectReason());
                    log.warn("素材准入不通过 materialId={}: {}", materialId, acceptance.rejectReason());
                    return List.of();
                }
            }
            // 质量预检（仅记录，不阻断）
            var quality = preChecker.evaluate(rawText, domain, spaceId);
            log.info("预检完成 materialId={} score={} grade={} estGrains={}-{} scenes={}",
                    materialId, quality.overallScore(), quality.grade(),
                    quality.estimatedGrainMin(), quality.estimatedGrainMax(), quality.detectedScenes());
        } catch (Exception e) {
            log.warn("预检异常，跳过继续萃取: materialId={}, error={}", materialId, e.getMessage());
        }

        // ── Layer 0: 情境标注（一次 AI 调用）──
        ContextTags context = tagContext(rawText, domain);
        log.info("情境标注完成, materialId={}: stage={}, persona={}, industry={}",
                materialId, context.buyingStage(), context.buyerPersona(), context.industrySignals());

        // ── Layer 1+2+3: 规则清洗（零AI调用）──
        self.updateMaterialStatus(materialId, "cleaning", null);

        String detectedType = MaterialNoiseCleaner.detectMaterialType(rawText, material.getFileName());
        String deNoised = noiseCleaner.cleanFormatNoise(rawText, detectedType);
        log.info("素材类型检测: materialId={}, fileName={}, detectedType={}", materialId, material.getFileName(),
                detectedType);
        String filtered = businessFilter.filterBusinessNoise(deNoised, domain);
        String normalized = normalizer.normalize(filtered);

        log.info("三层清洗完成, materialId={}: {}字→{}字→{}字→{}字",
                materialId, rawText.length(), deNoised.length(), filtered.length(), normalized.length());

        // ── Layer 4+5: AI 提取（语义理解，注入情境上下文）──
        self.updateMaterialStatus(materialId, "analyzing", null);

        // 淘汰记录收集器：管道内只收集，尾部短事务一次落库（记录失败不阻断萃取）
        List<ExtractionDropLog> drops = new ArrayList<>();

        List<TextChunk> chunks = semanticChunk(normalized);
        List<TextChunk> uniqueChunks = deduplicate(chunks, spaceId, materialId, drops);
        List<ExtractedInsight> insights = extractInsights(uniqueChunks, materialId, context, domain);
        List<GrainCandidate> candidates = classifyScenesBatch(insights, spaceId, materialId, domain);

        // ── Layer 6: 对抗验证（分批调用）──
        List<GrainCandidate> verified = verifyGrains(candidates, context, materialId, spaceId, domain, drops);
        int rejected = candidates.size() - verified.size();
        if (rejected > 0) {
            log.info("对抗验证剔除 {} 个低质量颗粒, materialId={}", rejected, materialId);
        }

        // ── Layer 7: 同标签合并去重 ──
        verified = mergeOverlapping(verified, materialId, domain);

        // ── Layer 8: 模式发现 ──
        PatternSummary patterns = discoverPatterns(verified, domain);

        // ── Layer 9: FAQ 提取（从清洗后原文提取客户异议+销售回应）──
        String faqJson = extractFaq(normalized, materialId, domain);

        // ── Layer 10: 叙事重放 + 策略-颗粒关联 ──
        String narrativeJson = generateNarrativeWithLinks(verified, patterns, domain);

        // ── Layer 11: 画像生成（异步，不阻塞清洗管道）──
        self.generateProfile(verified, patterns, context, spaceId, domain);

        // ── analyzed ──
        String analysisNotes = String.format(
                "三层清洗: %d→%d→%d→%d字 | 分块:%d | 去重后:%d | 候选:%d | 验证通过:%d",
                rawText.length(), deNoised.length(), filtered.length(), normalized.length(),
                chunks.size(), uniqueChunks.size(), candidates.size(), verified.size());
        // 构建元数据（含模式发现结果）
        Map<String, Object> metaMap = new LinkedHashMap<>();
        metaMap.put("context", Map.of(
                "buyingStage", context.buyingStage() != null ? context.buyingStage() : "",
                "buyerPersona", context.buyerPersona() != null ? context.buyerPersona() : "",
                "competitiveContext", context.competitiveContext() != null ? context.competitiveContext() : "",
                "dealSizeHint", context.dealSizeHint() != null ? context.dealSizeHint() : "",
                "industrySignals", context.industrySignals() != null ? context.industrySignals() : ""));
        metaMap.put("verifiedCount", verified.size());
        metaMap.put("rejectedCount", rejected);
        metaMap.put("mergedCount", verified.size());
        metaMap.put("reportVersion", "v" + material.getVersion() + "-"
                + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")));
        if (patterns != null) {
            metaMap.put("patterns", Map.of(
                    "coreHabits", patterns.coreHabits() != null ? patterns.coreHabits() : List.of(),
                    "differentiators", patterns.differentiators() != null ? patterns.differentiators() : List.of(),
                    "methodologyName", patterns.methodologyName() != null ? patterns.methodologyName() : "",
                    "oneliner", patterns.oneliner() != null ? patterns.oneliner() : ""));
        }
        // FAQ 和叙事存入元数据（JSON 字符串，前端/报告按需解析）
        if (faqJson != null) {
            try {
                metaMap.put("faq", objectMapper.readValue(faqJson, List.class));
            } catch (Exception ignored) {
            }
        }
        if (narrativeJson != null) {
            try {
                metaMap.put("narrative", objectMapper.readValue(narrativeJson, Map.class));
            } catch (Exception ignored) {
            }
        }
        self.finalizeMaterialCleaning(materialId, analysisNotes, toJson(metaMap));

        // 淘汰明细落库（排查"颗粒为什么没出来"；失败仅告警，不阻断主流程）
        if (!drops.isEmpty()) {
            try {
                self.saveDropLogs(drops);
                log.info("淘汰记录落库, materialId={}, count={}", materialId, drops.size());
            } catch (Exception e) {
                log.warn("淘汰记录落库失败, materialId={}, count={}: {}", materialId, drops.size(), e.getMessage());
            }
        }

        return verified;
    }

    private UUID getSpaceIdBySkill(UUID skillId) {
        return skillRepository.findById(skillId)
                .map(s -> s.getSpaceId())
                .orElseThrow(() -> new RuntimeException("Skill not found: " + skillId));
    }

    // ---- 智能分块（基于句子边界，保证语义完整）----
    private static final int CHUNK_TARGET = 800;
    private static final int CHUNK_MAX = 1500;

    private List<TextChunk> semanticChunk(String text) {
        // Step 1: 拆成句子（按中文句号、问号、感叹号、换行切分）
        List<String> sentences = new ArrayList<>();
        StringBuilder sentenceBuf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            sentenceBuf.append(ch);
            // 句子边界：句号、问号、感叹号、换行
            if (ch == '。' || ch == '！' || ch == '？' || ch == '\n' || ch == '.' || ch == '!' || ch == '?') {
                String s = sentenceBuf.toString().trim();
                if (!s.isEmpty() && !isNoise(s)) {
                    sentences.add(s);
                }
                sentenceBuf = new StringBuilder();
            }
        }
        // 尾巴
        String tail = sentenceBuf.toString().trim();
        if (!tail.isEmpty() && !isNoise(tail)) {
            sentences.add(tail);
        }

        // Step 2: 攒句子到 chunk，目标 800 字，不超过 1500
        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int idx = 0;

        for (String sentence : sentences) {
            // 当前 buffer + 新句子超过上限，先切
            if (buffer.length() > 0 && buffer.length() + sentence.length() > CHUNK_MAX) {
                chunks.add(new TextChunk(idx++, buffer.toString().trim()));
                buffer = new StringBuilder();
            }
            buffer.append(sentence);
            // 达到目标长度且 ≥ 500，也切（避免 chunk 太小）
            if (buffer.length() >= CHUNK_TARGET && buffer.length() >= 500) {
                chunks.add(new TextChunk(idx++, buffer.toString().trim()));
                buffer = new StringBuilder();
            }
        }

        if (buffer.length() > 0) {
            chunks.add(new TextChunk(idx, buffer.toString().trim()));
        }

        return chunks;
    }

    private boolean isNoise(String text) {
        {
            if (text.matches("^[\\d\\s\\.\\-\\|/：:]+$"))
                return true;
        }
        if (text.length()

                < 15 && !text.matches(".*[\\u4e00-\\u9fa5].*")) {
            return true;

        }
        // 纯时间戳行
        if (text.matches("^\\d{2}:\\d{2}:\\d{2}$"))
            return true;
        return false;
    }

    // ---- 去重 ----
    private List<TextChunk> deduplicate(List<TextChunk> chunks, UUID spaceId, UUID materialId,
            List<ExtractionDropLog> drops) {
        var existingGrains = grainRepository.findBySpaceId(spaceId);
        {
            if (existingGrains.isEmpty())
                return chunks;
        }

        return chunks.stream()
                .filter(chunk -> {
                    // 用文本相似度做近似去重（不依赖 embedding，首批素材也能用）
                    for (var g : existingGrains) {
                        String grainText = buildGrainText(g);
                        double sim = textSimilarity(chunk.text(), grainText);
                        if (sim > 0.7) {
                            log.info("chunk#{} 文本高度重复，跳过", chunk.index());
                            drops.add(ExtractionDropLog.builder()
                                    .materialId(materialId)
                                    .spaceId(spaceId)
                                    .stage("dedup")
                                    .chunkIndex(chunk.index())
                                    .contentPreview(truncate(chunk.text(), 500))
                                    .collidedGrainId(g.getId())
                                    .similarity(BigDecimal.valueOf(sim).setScale(3, RoundingMode.HALF_UP))
                                    .build());
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    private String buildGrainText(com.aiextract.model.ExperienceGrain g) {
        return String.join(" ",
                g.getSceneDescription() != null ? g.getSceneDescription() : "",
                g.getExpertThought() != null ? g.getExpertThought() : "",
                g.getStandardScript() != null ? g.getStandardScript() : "").trim();
    }

    /** Jaccard 字符 n-gram 相似度（3-gram），零 embedding 成本 */
    private double textSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty())
            return 0;
        Set<String> setA = ngrams(a, 3);
        Set<String> setB = ngrams(b, 3);
        if (setA.isEmpty() && setB.isEmpty())
            return 1;
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return (double) intersection.size() / union.size();
    }

    private Set<String> ngrams(String text, int n) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i <= text.length() - n; i++) {
            result.add(text.substring(i, i + n));
        }
        return result;
    }

    // ---- AI 关键提取（渐进式 prompt，减少重复指令开销）----

    /** 完整版提取 prompt — 首个 chunk 使用（含情境上下文） */
    private List<ExtractedInsight> extractInsights(List<TextChunk> chunks, UUID materialId, ContextTags context,
            String domain) {
        String contextPrefix = context.toPromptPrefix();
        List<ExtractedInsight> all = java.util.Collections.synchronizedList(new ArrayList<>());

        // 并行调 AI（每 5 个 chunk 一批，控制并发避免限流）
        int batchSize = 5;
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = start; i < end; i++) {
                final int idx = i;
                TextChunk chunk = chunks.get(i);
                futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        String prompt = (idx == 0)
                                ? promptLoader.format("material_extract_full.md", Map.of(
                                        "context_prefix", contextPrefix, "material_content", chunk.text()), domain)
                                : promptLoader.format("material_extract_short.md", Map.of(
                                        "material_content", chunk.text()), domain);
                        String json = chatStreamAdapter.chat(prompt);
                        List<Map<String, Object>> items = parseJsonArray(json);
                        for (var item : items) {
                            double conf = ((Number) item.getOrDefault("confidence", 0)).doubleValue();
                            if (conf < 0.7) {

                                continue;

                            }
                            all.add(new ExtractedInsight(
                                    (String) item.get(KEY_SCENE_DESCRIPTION),
                                    (String) item.get(KEY_EXPERT_THOUGHT),
                                    (String) item.get(KEY_STANDARD_SCRIPT),
                                    (String) item.get(KEY_COMMON_MISTAKES),
                                    (String) item.get(KEY_APPLICABLE_CONDITION),
                                    conf));
                        }
                    } catch (Exception e) {
                        log.warn("chunk#{} 提取失败: {}", chunk.index(), e.getMessage());
                    }
                }, cleanExecutor));
            }
            // 等待当前批次完成
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .join();
        }
        return all;
    }

    // ---- 场景归类（批量版 — 一次 AI 调用打所有标签）----

    private List<GrainCandidate> classifyScenesBatch(
            List<ExtractedInsight> insights, UUID spaceId, UUID materialId, String domain) {

        {
            if (insights.isEmpty())
                return List.of();
        }

        Set<String> existingTags = grainRepository.findBySpaceId(spaceId).stream()
                .filter(g -> g.getSceneTag() != null)
                .map(g -> g.getSceneTag())
                .collect(Collectors.toSet());

        // 构建批量 prompt：把所有场景描述编号后一次发送
        StringBuilder scenesBlock = new StringBuilder();
        for (int i = 0; i < insights.size(); i++) {
            scenesBlock.append(String.format("[%d] %s\n", i,
                    insights.get(i).sceneDescription() != null ? insights.get(i).sceneDescription() : "无描述"));
        }

        String existingTagsStr = existingTags.isEmpty() ? "无" : String.join(",", existingTags);
        DomainConfig dc = domainConfigLoader.load(domain);
        String domainName = dc != null && dc.getDomain() != null ? dc.getDomain().getName() : "领域";
        String prompt = promptLoader.format("classify_scenes.md", Map.of(
                "domain_name", domainName,
                "existing_tags", existingTagsStr,
                "scenes_block", scenesBlock.toString()), domain);

        try {
            String json = chatStreamAdapter.chat(prompt);
            if (json == null) {

                return fallbackClassify(insights, materialId);

            }

            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
            }

            List<Map<String, Object>> items = objectMapper
                    .readValue(clean, new TypeReference<List<Map<String, Object>>>() {
                    });

            // index → tag 映射
            Map<Integer, String> tagMap = new HashMap<>();
            for (var item : items) {
                int idx = ((Number) item.get(KEY_INDEX)).intValue();
                String tag = (String) item.get(KEY_TAG);
                if (tag != null && !tag.isBlank()) {
                    tagMap.put(idx, tag.trim());
                }
            }

            List<GrainCandidate> result = new ArrayList<>();
            for (int i = 0; i < insights.size(); i++) {
                String tag = tagMap.getOrDefault(i, "通用技巧");
                result.add(new GrainCandidate(tag, insights.get(i), materialId, null, null, null));
            }
            return result;
        } catch (Exception e) {
            log.warn("批量场景归类失败: {}", e.getMessage());
            return fallbackClassify(insights, materialId);
        }
    }

    /** 降级：全部打"通用技巧"标签 */
    private List<GrainCandidate> fallbackClassify(List<ExtractedInsight> insights, UUID materialId) {
        return insights.stream()
                .map(insight -> new GrainCandidate("通用技巧", insight, materialId, null, null, null))
                .collect(Collectors.toList());
    }

    // ---- JSON 解析 ----

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonArray(String raw) {
        try {
            String json = raw.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*|```\\s*", "").trim();
            }
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("JSON解析失败: {}", raw.substring(0, Math.min(100, raw.length())));
            return List.of();
        }
    }

    // ---- 内部类型 ----
    record TextChunk(int index, String text) {
    }

    record ExtractedInsight(String sceneDescription, String expertThought, String standardScript,
            String commonMistakes, String applicableCondition, double confidence) {
    }

    public record GrainCandidate(String sceneTag, ExtractedInsight insight, UUID sourceMaterialId,
            Double qualityScore, String difficultyLevel, String verificationNotes) {
    }

    record ContextTags(String buyingStage, String buyerPersona, String competitiveContext,
            String dealSizeHint, String industrySignals) {
        String toPromptPrefix() {
            return String.format("""
                    ## 对话情境
                    购买阶段：%s | 买方角色：%s | 竞争态势：%s
                    行业：%s | 预估单量：%s
                    """,
                    buyingStage != null ? buyingStage : "未知",
                    buyerPersona != null ? buyerPersona : "未知",
                    competitiveContext != null ? competitiveContext : "未知",
                    industrySignals != null ? industrySignals : "未知",
                    dealSizeHint != null ? dealSizeHint : "未知");
        }
    }

    record PatternSummary(List<String> coreHabits, List<String> differentiators,
            String methodologyName, String oneliner) {
    }

    // ---- 画像生成（Layer 11）—— 异步化，不阻塞清洗管道 ----

    @org.springframework.scheduling.annotation.Async("embeddingExecutor")
    void generateProfile(List<GrainCandidate> grains, PatternSummary patterns,
            ContextTags context, UUID spaceId, String domain) {
        {
            if (grains.isEmpty())
                return;
        }
        try {
            StringBuilder grainBlock = new StringBuilder();
            for (int i = 0; i < Math.min(grains.size(), 10); i++) {
                var g = grains.get(i);
                grainBlock.append(String.format("%s: %s | %s\n", g.sceneTag(),
                        truncate(g.insight().expertThought(), 50),
                        truncate(g.insight().standardScript(), 50)));
            }

            String habits = patterns != null && patterns.coreHabits() != null
                    ? String.join("; ", patterns.coreHabits())
                    : "";
            String diffs = patterns != null && patterns.differentiators() != null
                    ? String.join("; ", patterns.differentiators())
                    : "";
            String industry = context.industrySignals() != null ? context.industrySignals() : "";
            String persona = context.buyerPersona() != null ? context.buyerPersona() : "";

            String prompt = promptLoader.format("material_profile.md", Map.of(
                    "grains_block", grainBlock.toString(), "habits", habits,
                    "diffs", diffs, "industry", industry, "persona", persona), domain);
            String json = chatStreamAdapter.chat(prompt);
            if (json == null) {

                return;

            }

            String clean = json.trim();
            {
                if (clean.startsWith("```"))
                    clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
            }

            Map<String, Object> map = objectMapper.readValue(clean, Map.class);

            com.aiextract.model.Skill skill = skillRepository.findBySpaceId(spaceId).orElse(null);
            if (skill == null) {

                return;

            }

            com.aiextract.model.SkillProfile profile = profileRepository.findBySkillId(skill.getId())
                    .orElse(com.aiextract.model.SkillProfile.builder().skillId(skill.getId()).build());

            profile.setPersonality((String) map.get(KEY_PERSONALITY));
            profile.setSpeakingStyle((String) map.get(KEY_SPEAKING_STYLE));
            profile.setBackground((String) map.get(KEY_BACKGROUND));
            profile.setCommonPhrases((String) map.get(KEY_COMMON_PHRASES));
            profile.setKnowledgeDomains(toJson(map.get(KEY_KNOWLEDGE_DOMAINS)));
            profile.setCommunicationPreferences(toJson(map.get(KEY_COMMUNICATION_PREFERENCES)));
            profileRepository.save(profile);

            log.info("画像自动生成完成, skillId: {}, personality: {}", skill.getId(),
                    truncate((String) map.get(KEY_PERSONALITY), 30));
        } catch (Exception e) {
            log.warn("画像生成失败: {}", e.getMessage());
        }
    }

    // ---- FAQ 提取（Layer 9）----

    private String extractFaq(String cleanedText, UUID materialId, String domain) {
        try {
            String prompt = promptLoader.format("material_faq.md", Map.of(
                    "dialogue_text", sampleText(cleanedText, 4000)), domain);
            String json = chatStreamAdapter.chat(prompt);
            if (json == null) {

                return null;

            }
            String clean = json.trim();
            {
                if (clean.startsWith("```"))
                    clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
            }
            // 验证JSON合法性
            objectMapper.readTree(clean);
            log.info("FAQ提取完成, materialId={}", materialId);
            return clean;
        } catch (Exception e) {
            log.warn("FAQ提取失败: {}", e.getMessage());
            return null;
        }
    }

    // ---- 叙事重放 + 策略颗粒关联（Layer 10）----

    private String generateNarrativeWithLinks(List<GrainCandidate> grains, PatternSummary patterns, String domain) {
        try {
            StringBuilder grainBlock = new StringBuilder();
            for (int i = 0; i < grains.size(); i++) {
                var g = grains.get(i);
                grainBlock.append(String.format("[%d] %s: %s | %s\n", i, g.sceneTag(),
                        truncate(g.insight().sceneDescription(), 60),
                        truncate(g.insight().standardScript(), 60)));
            }

            String patternsStr = "{}";
            if (patterns != null) {
                patternsStr = String.format("{\"habits\":%s,\"diffs\":%s}",
                        patterns.coreHabits() != null ? patterns.coreHabits() : "[]",
                        patterns.differentiators() != null ? patterns.differentiators() : "[]");
            }

            String prompt = promptLoader.format("material_narrative.md", Map.of(
                    "grains_block", grainBlock.toString(), "patterns_str", patternsStr), domain);
            String json = chatStreamAdapter.chat(prompt);
            if (json == null) {

                return null;

            }
            String clean = json.trim();
            {
                if (clean.startsWith("```"))
                    clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
            }
            objectMapper.readTree(clean);
            log.info("叙事重放+策略关联完成, grains={}", grains.size());
            return clean;
        } catch (Exception e) {
            log.warn("叙事重放失败: {}", e.getMessage());
            return null;
        }
    }
}
