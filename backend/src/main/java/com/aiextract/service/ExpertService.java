package com.aiextract.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.reactive.function.client.WebClient;
import com.aiextract.config.PromptLoader;
import com.aiextract.dto.*;
import com.aiextract.common.ErrorMessages;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.*;
import com.aiextract.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 萃取师专家经验库服务
 *
 * <p>管理萃取师经验的完整生命周期：上传材料、提取颗粒、审核激活、
 * 文件管理和综合Skill生成。13个核心方法覆盖全部API接口。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpertService {

    private final ExpertSkillRepository expertSkillRepository;
    private final ExpertGrainRepository expertGrainRepository;
    private final ExpertDocumentRepository expertDocumentRepository;
    private final ReportRepository reportRepository;
    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;
    private final WebClient webClient;
    private final PromptLoader promptLoader;
    private final com.aiextract.config.DomainConfigLoader domainConfigLoader;

    @Autowired
    @Lazy
    private ExpertService self;

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    @Value("${storage.local.path}")
    private String storageBasePath;

    /** 获取可用萃取师列表 */
    @Transactional(readOnly = true)
    public List<ExpertAvailableResponse> getAvailableExperts(String domain) {
        List<ExpertSkill> activeExperts = domain != null && !domain.isBlank()
                ? expertSkillRepository.findByStatusAndDomain("active", domain)
                : expertSkillRepository.findByStatus("active");
        List<ExpertAvailableResponse> list = new ArrayList<>();

        list.add(ExpertAvailableResponse.builder().id("").name("综合（使用所有萃取师）").type("composite").build());
        for (ExpertSkill e : activeExperts) {
            list.add(ExpertAvailableResponse.builder()
                    .id(e.getId().toString()).name(e.getName()).type("single")
                    .styleTags(parseJsonList(e.getStyleTags()))
                    .industryTags(parseJsonList(e.getIndustryTags())).build());
        }
        list.add(ExpertAvailableResponse.builder().id("none").name("不使用萃取师经验（基础版）").type("none").build());
        return list;

        }
    /** 
     * 获取萃取师管理列表 
     * */
    @Transactional(readOnly = true)
    public Page<ExpertSkillListResponse> getExperts(int page, int size, String keyword, String status) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<ExpertSkill> expertPage;
        if (keyword != null && !keyword.isEmpty()) {
            expertPage = expertSkillRepository.findByNameContainingIgnoreCase(keyword, pageable);
        } else if (status != null && !status.isEmpty()) {
            expertPage = expertSkillRepository.findByStatus(status, pageable);
        } else {
            expertPage = expertSkillRepository.findAll(pageable);
        }

        // 批量预加载文档数和颗粒数，避免 toListResponse 里 N+1
        List<UUID> expertIds = expertPage.getContent().stream().map(ExpertSkill::getId).toList();
        Map<UUID, Long> docCounts = expertDocumentRepository.countByExpertIdIn(expertIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));
        Map<UUID, Long> grainCounts = expertGrainRepository.countByExpertIdIn(expertIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));

        return expertPage.map(e -> toListResponse(e, docCounts, grainCounts));
    }
    /** 获取萃取师详情 */
    @Transactional(readOnly = true)
    public ExpertSkillDetailResponse getExpertDetail(String expertId) {
        UUID id = UUID.fromString(expertId);
        ExpertSkill expert = expertSkillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.EXPERT_NOT_FOUND));

        List<ExpertGrain> grains = expertGrainRepository.findByExpertId(id);
        Map<String, List<ExpertGrain>> grouped = grains.stream()
                .collect(Collectors.groupingBy(g -> g.getCategory() != null ? g.getCategory() : "未分类"));

        List<ExpertSkillDetailResponse.ExpertGrainGroup> grainGroups = grouped.entrySet().stream()
                .map(entry -> ExpertSkillDetailResponse.ExpertGrainGroup.builder()
                        .category(entry.getKey())
                        .grains(entry.getValue().stream().map(g -> ExpertSkillDetailResponse.ExpertGrainInfo.builder()
                                .id(g.getId().toString()).category(g.getCategory()).sourceType(g.getSourceType())
                                .sceneDescription(g.getSceneDescription()).knowledgeContent(g.getKnowledgeContent())
                                .applicationRule(g.getApplicationRule()).priority(g.getPriority())
                                .consensusType(g.getConsensusType()).status(g.getStatus()).build()).toList())
                        .build()).toList();

        List<ExpertDocument> docs = expertDocumentRepository.findByExpertId(id);
        List<ExpertSkillDetailResponse.ExpertDocumentInfo> docInfos = docs.stream()
                .map(d -> ExpertSkillDetailResponse.ExpertDocumentInfo.builder()
                        .id(d.getId().toString()).fileName(d.getFileName()).fileUrl(d.getFileUrl())
                        .fileType(d.getFileType()).fileSize(d.getFileSize()).status(d.getStatus()).build()).toList();

        return ExpertSkillDetailResponse.builder()
                .id(expert.getId().toString()).name(expert.getName()).description(expert.getDescription())
                .sourceType(expert.getSourceType()).styleTags(parseJsonList(expert.getStyleTags()))
                .industryTags(parseJsonList(expert.getIndustryTags())).seniority(expert.getSeniority())
                .skillFile(expert.getSkillFile()).grainCount(expert.getGrainCount()).status(expert.getStatus())
                .grainGroups(grainGroups).documents(docInfos)
                .createdAt(toString(expert.getCreatedAt())).updatedAt(toString(expert.getUpdatedAt())).build();
    }

    /** 上传材料（支持新建 + 追加已有萃取师） */
    @Transactional(rollbackFor = Exception.class)
    public ExpertSkillDetailResponse uploadExpertMaterials(
            String name, String description, List<String> styleTags,
            List<String> industryTags, String seniority, List<Map<String, Object>> files,
            String domain, String existingExpertId) {
        // 追加模式：追加文件到已有萃取师
        if (existingExpertId != null && !existingExpertId.isBlank()) {
            return addDocumentsToExpert(existingExpertId, files);
        }

        // 新建模式：domain 必填
        if (domain == null || domain.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "新建萃取师时领域(domain)不能为空");
        }


        LocalDateTime now = LocalDateTime.now();
        ExpertSkill expert = ExpertSkill.builder().id(UUID.randomUUID()).name(name)
                .description(description).sourceType("document").domain(domain)
                .styleTags(toJson(styleTags))
                .industryTags(toJson(industryTags)).seniority(seniority).grainCount(0)
                .status("pending").createdAt(now).updatedAt(now).build();
        expertSkillRepository.save(expert);;

        // 文件记录——按人分目录，同名自动编号
        String basePath = storageBasePath != null && !storageBasePath.isBlank() ? storageBasePath : "data/files";
        String baseDir = basePath + "/experts/" + expert.getId() + "/";
        java.util.Set<String> batchNames = new java.util.HashSet<>();

        List<ExpertDocument> docs = new ArrayList<>();
        for (Map<String, Object> file : files) {
            String originalName = (String) file.get("fileName");
            String safeName = resolveFileName(baseDir, originalName, batchNames);
            String fileUrl = baseDir + safeName;
            docs.add(ExpertDocument.builder().id(UUID.randomUUID()).expertId(expert.getId())
                    .fileName(originalName).fileUrl(fileUrl)
                    .fileType((String) file.get("fileType")).fileSize(((Number) file.getOrDefault("fileSize", 0)).longValue())
                    .status("uploaded").createdAt(now).build());
        }
        expertDocumentRepository.saveAll(docs);

        // 上传后状态置为 pending，由 ExpertAnalysisScheduler 定时扫描处理
        log.info("萃取师材料已上传（待调度分析）, expertId: {}", expert.getId());
        return getExpertDetail(expert.getId().toString());
    }


    /**
     * 分析萃取师材料（由 ExpertAnalysisScheduler 定时调度调用）
     *
     * <p>从 expert_document 表加载已上传文档，
     * 尝试调用 AI 服务解析并生成报告，
     * 最终将状态更新为 extracting（AI成功）或 failed（异常）。</p>
     */
    /** 短事务：更新萃取师状态 */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void updateExpertStatus(UUID expertId, String status) {
        ExpertSkill expert = expertSkillRepository.findById(expertId)
                .orElseThrow(() -> new BusinessException(404, "萃取师不存在: " + expertId));
        expert.setStatus(status);
        expertSkillRepository.save(expert);
        log.info("萃取师状态更新 expertId={} status={}", expertId, status);
    }


    /** 异步分析（无 @Transactional，AI/HTTP 在事务外） */
    public void analyzeMaterials(UUID expertId) {
        log.info("开始异步分析萃取师材料, expertId: {}", expertId);
        ExpertSkill expert = expertSkillRepository.findById(expertId).orElse(null);
        if (expert == null) {
            return;
        }

        // 标记为分析中（短事务）
        self.updateExpertStatus(expertId, "analyzing");

        // interview 来源：直接使用 sourceContent，跳过文件解析步骤
            if ("interview".equals(expert.getSourceType())
                    && expert.getSourceContent() != null
                    && !expert.getSourceContent().isBlank()) {
                processInterviewSource(expert, expert.getSourceContent());
                return;
            }
            // 读取已上传文档，先解析文件内容
            List<ExpertDocument> docs = expertDocumentRepository.findByExpertId(expertId);

            // 步骤1：调用Python服务解析文件
            boolean hasPendingManual = false;
            for (ExpertDocument doc : docs) {
                if (doc.getParsedContent() != null && "pending_manual".equals(doc.getStatus())) {
                    hasPendingManual = true; continue;
                }

                if (doc.getParsedContent() == null && !"parsed".equals(doc.getStatus()) && !"pending_manual".equals(doc.getStatus())) {
                    try {
                        String fileUrl = doc.getFileUrl() != null ? doc.getFileUrl()
                                : "/data/files/experts/" + expertId + "/" + doc.getFileName();
                        Map<String, String> parseResult = webClient.post()
                                .uri(aiServiceUrl + "/internal/parse-file")
                                .bodyValue(Map.of("file_path", fileUrl, "file_name", doc.getFileName()))
                                .retrieve()
                                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, String>>() {})
                                .timeout(java.time.Duration.ofSeconds(120))
                                .block();
                        if (parseResult != null && parseResult.containsKey("text")) {
                            doc.setParsedContent(parseResult.get("text"));
                            // 图片/音频需要手动处理
                            boolean needsManual = Boolean.TRUE.equals(parseResult.get("needs_manual"))
                                    || (parseResult.get("needs_manual") instanceof String
                                        && "true".equalsIgnoreCase((String) parseResult.get("needs_manual")));
                            if (needsManual) {
                                doc.setStatus("pending_manual");
                                hasPendingManual = true;
                                log.info("文件需手动处理, docId: {}, type: {}", doc.getId(), doc.getFileType());
                            } else {
                                doc.setStatus("parsed");
                            }
                            expertDocumentRepository.save(doc);
                        }
                    } catch (Exception parseEx) {
                        log.error("文件解析失败, docId: {}, fileName: {}, reason: {}",
                                doc.getId(), doc.getFileName(), parseEx.getMessage());
                        doc.setStatus("failed");
                        expertDocumentRepository.save(doc);
                        throw new RuntimeException("文件解析失败, expertId=" + expertId
                                + ", docId=" + doc.getId(), parseEx);
                    }
                }
            }

            // 如果有待手动处理的文件，跳过AI分析
            if (hasPendingManual) {
                log.info("萃取师存在待手动处理的文件，跳过AI分析, expertId: {}", expertId);
                expert.setStatus("pending");
                expertSkillRepository.save(expert);
                return;
            }

            // 步骤2：将解析后的文档内容传递给AI分析
            try {
                List<Map<String, String>> docContents = docs.stream()
                        .filter(d -> d.getParsedContent() != null && d.getParsedContent().length() > 10)
                        .map(d -> Map.of("fileName", d.getFileName(), "content", d.getParsedContent()))
                        .toList();

                // 构建文件内容文本
                StringBuilder fileContents = new StringBuilder();
                if (!docContents.isEmpty()) {
                    for (Map<String, String> contentDoc : docContents) {
                        fileContents.append("=== ").append(contentDoc.get("fileName")).append(" ===\n");
                        fileContents.append(contentDoc.get("content")).append("\n\n");
                    }
                }

                String rawContent = callAiForExpertReport(expert,
                        !fileContents.isEmpty() ? fileContents.toString() : "（无上传文件）",
                        "请分析以上材料，输出完整的《萃取师经验报告》JSON");

                if (rawContent != null && rawContent.length() > 10) {
                    String domain = resolveExpertDomain(expert);
                    String structuredJson = structureExpertReport(rawContent, expert.getName(), domain);
                    if (rawContent.length() > 50) {
                        Report report = Report.builder()
                                .id(UUID.randomUUID())
                                .title(expert.getName() + "·萃取师经验报告")
                                .contentJson(structuredJson)
                                .shareEnabled(true)
                                .rating(java.math.BigDecimal.valueOf(4.5))
                                .viewCount(0)
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build();
                        reportRepository.save(report);
                        expert.setReportId(report.getId());
                        // 追加报告ID到历史列表（用于后续全量提取）
                        appendReportIdToHistory(expert, report.getId());
                        log.info("萃取师AI报告已生成, expertId: {}, contentLength: {}", expertId, structuredJson.length());
                    }
                }

                expert.setStatus("extracting");
                expertSkillRepository.save(expert);
                // 自动触发颗粒提取
                try {
                    self.extractGrains(expertId.toString());
                } catch (Exception e) {
                    log.warn("自动提取颗粒失败, expertId: {}, 可稍后手动提取", expertId, e);
                }

                log.info("萃取师材料分析完成, expertId: {}", expertId);
            } catch (Exception aiEx) {
                log.error("AI分析萃取师材料失败, expertId: {}", expertId, aiEx);
                expert.setStatus("failed");
                expertSkillRepository.save(expert);
                throw new RuntimeException("AI分析萃取师材料失败: " + aiEx.getMessage(), aiEx);
            }
    }

    /** 提取经验颗粒 — AI 调用在事务外，避免长事务持连接。
     *  只处理最新一份报告（每次重分析=覆盖重写旧颗粒），旧报告作为档案保留不参与抽取。
     *  事务安全：暂存旧颗粒 id 集合，AI 成功后才在短事务内 deleteByIdIn + saveAll。 */
    public void extractGrains(String expertId) {
        UUID id = UUID.fromString(expertId);
        ExpertSkill expert = expertSkillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.EXPERT_NOT_FOUND));

        // 只取最新一份报告的 content_json（覆盖重写语义）
        UUID reportId = expert.getReportId();
        if (reportId == null) {
            log.info("萃取师无报告，跳过法则提取, expertId={}", expertId);
            return;
        }

        Report report = reportRepository.findById(reportId).orElse(null);
        if (report == null || report.getContentJson() == null) {
            log.info("萃取师报告无内容，跳过法则提取, expertId={}, reportId={}", expertId, reportId);
            return;
        }


        // 暂存旧颗粒 id（仅在 AI 成功后删除），active 颗粒排除
        List<UUID> oldIds = expertGrainRepository.findByExpertId(id).stream()
                .filter(g -> !"active".equals(g.getStatus()))
                .map(com.aiextract.model.ExpertGrain::getId)
                .toList();

        // AI 调用在事务外
        extractGrainsFromReportContent(id, expert.getSourceType(),
                report.getContentJson(), resolveExpertDomain(expert));

        // AI 成功后短事务：删旧颗粒 + 计新数量
        self.replaceNonActiveGrains(id, oldIds);
        log.info("萃取法则提取完成, expertId: {}, reportId: {}, oldCleared: {}",
                expertId, reportId, oldIds.size());
    }

    /** 短事务：删除非 active 旧颗粒 + 更新 grain_count */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void replaceNonActiveGrains(UUID expertId, List<UUID> oldIds) {
        if (!oldIds.isEmpty()) {
            expertGrainRepository.deleteAllById(oldIds);
        }

        ExpertSkill expert = expertSkillRepository.findById(expertId)
                .orElse(null);
        if (expert != null) {
            expert.setGrainCount((int) expertGrainRepository.findByExpertId(expertId).size());
            expert.setStatus("extracting");
            expertSkillRepository.save(expert);
        }
    }

    /** 追加报告ID到历史列表 */
    private void appendReportIdToHistory(ExpertSkill expert, UUID reportId) {
        List<UUID> history = getReportIdHistory(expert);
        if (!history.contains(reportId)) {
            history.add(reportId);
            saveSkillFileMeta(expert, null, history);}
    }

    /** 获取全部历史报告ID */
    private List<UUID> getReportIdHistory(ExpertSkill expert) {
        List<UUID> ids = new ArrayList<>();
        try {
            if (expert.getSkillFile() != null && !expert.getSkillFile().isEmpty()) {
                String raw = expert.getSkillFile();
                // 兼容旧格式（纯JSON数组）
                if (raw.trim().startsWith("[")) {
                    List<String> strIds = objectMapper.readValue(raw, List.class);
                    for (String s : strIds) {
                        try { ids.add(UUID.fromString(s)); } catch (Exception ignored) {}
                    }
                } else {
                    // 新格式：JSON对象 {promptPath, reportIds}
                    Map<String, Object> meta = objectMapper.readValue(raw, Map.class);
                    Object reportIdsObj = meta.get("reportIds");
                    if (reportIdsObj instanceof List) {
                        for (Object item : (List<?>) reportIdsObj) {
                            try { ids.add(UUID.fromString(item.toString())); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            // JSON解析失败，忽略
        }
        return ids;
    }

    /** 保存skillFile元数据（promptPath + reportIds 合并存储） */
    private void saveSkillFileMeta(ExpertSkill expert, String promptPath, List<UUID> reportIds) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        // 保留已有的 promptPath（如果未传入新的）
        String existingPath = getSkillFilePromptPath(expert);
        meta.put("promptPath", promptPath != null ? promptPath : existingPath);
        meta.put("reportIds", reportIds != null ? reportIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.toList()) : java.util.List.of());
        try {
            expert.setSkillFile(objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            expert.setSkillFile("{}");
        }
    }

    /** 从skillFile中读取promptPath */
    private String getSkillFilePromptPath(ExpertSkill expert) {
        try {
            if (expert.getSkillFile() != null && !expert.getSkillFile().isEmpty()) {
                String raw = expert.getSkillFile();
                if (raw.trim().startsWith("{")) {
                    Map<String, Object> meta = objectMapper.readValue(raw, Map.class);
                    return (String) meta.getOrDefault("promptPath", "");
                }
            }
        } catch (Exception ignored) {}
        return "";
    }


    /**
     * 从AI生成的七章报告中拆解专家颗粒
     */
    private void extractGrainsFromReportContent(UUID expertId, String sourceType, String contentJson, String domain) {
        log.info("开始AI拆解专家颗粒, expertId: {}", expertId);
        try {
            String systemPrompt = promptLoader.format("expert_grain_extraction.md", Map.of(
                    "report_json", contentJson != null ? contentJson : "{}"
            ), domain);

            String jsonStr = chatClient.prompt()
                    .system(systemPrompt)
                    .user("请从以上报告中提取专家颗粒，输出JSON数组")
                    .call()
                    .content();

            if (jsonStr != null && !jsonStr.isEmpty()) {
                jsonStr = jsonStr.trim();
                if (jsonStr.startsWith("```")) {
                    jsonStr = jsonStr.replaceAll("```json\\s*|```\\s*", "").trim();
                }

                LocalDateTime now = LocalDateTime.now();
                try {
                    List<Map<String, Object>> grainList = objectMapper.readValue(
                            jsonStr, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                    List<ExpertGrain> grains = new ArrayList<>();
                    for (Map<String, Object> g : grainList) {
                        grains.add(ExpertGrain.builder()
                                .id(UUID.randomUUID()).expertId(expertId)
                                .category(str(g.get("category"))).sourceType(sourceType)
                                .domain(domain)
                                .knowledgeContent(str(g.get("knowledgeContent")))
                                .applicationRule(str(g.get("applicationRule")))
                                .priority(g.get("priority") instanceof Number ? ((Number) g.get("priority")).intValue() : 3)
                                .consensusType("single").status("under_review")
                                .createdAt(now).updatedAt(now).build());
                    }

                    expertGrainRepository.saveAll(grains);
                    log.info("AI专家颗粒拆解完成, expertId: {}, count: {}", expertId, grainList.size());
                    return;
                } catch (Exception parseEx) {
                    log.warn("AI专家颗粒JSON解析失败，使用降级颗粒, expertId: {}, reason: {}", expertId, parseEx.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("AI专家颗粒拆解异常，使用降级颗粒, expertId: {}, reason: {}", expertId, e.getMessage());
        }


        // AI失败时抛出异常，不生成虚假数据
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "AI提取专家颗粒失败，请稍后重试");
    }

    private String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    /** 编辑颗粒 */
    @Transactional(rollbackFor = Exception.class)
    public void editGrain(String expertId, String grainId, ExpertGrainEditRequest request) {
        UUID gid = UUID.fromString(grainId);
        ExpertGrain grain = expertGrainRepository.findById(gid).orElse(null);
        if (grain == null) {
            throw new BusinessException(404, "颗粒不存在: " + grainId);
        }

        if (request.getCategory() != null) grain.setCategory(request.getCategory());
        if (request.getApplicationRule() != null) grain.setApplicationRule(request.getApplicationRule());
        if (request.getPriority() != null) grain.setPriority(request.getPriority());
        if (request.getStatus() != null) grain.setStatus(request.getStatus());
        expertGrainRepository.save(grain);
        log.info("颗粒已编辑, grainId: {}", grainId);;
    }

    /** 删除萃取师及其所有关联数据（颗粒+文档级联删除） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteExpert(String expertId) {
        UUID id = UUID.fromString(expertId);
        ExpertSkill expert = expertSkillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.EXPERT_NOT_FOUND));
        expertSkillRepository.delete(expert);
        log.info("萃取师已删除, expertId: {}, name: {}", expertId, expert.getName());}


    /** 删除颗粒 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteGrain(String expertId, String grainId) {
        UUID gid = UUID.fromString(grainId);
        expertGrainRepository.deleteById(gid);
        log.info("颗粒已删除, grainId: {}", grainId);
    }


    /** 激活Skill */
    @Transactional(rollbackFor = Exception.class)
    public void activateExpert(String expertId) {
        UUID id = UUID.fromString(expertId);
        ExpertSkill expert = expertSkillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.EXPERT_NOT_FOUND));

        List<ExpertGrain> underReview = expertGrainRepository.findByExpertIdAndStatus(id, "under_review");
        for (ExpertGrain g : underReview) { g.setStatus("active"); }
        expertGrainRepository.saveAll(underReview);

        // 生成萃取师MD文件路径，合并存入skillFile（保留报告ID历史）
        String promptPath = "/prompts/experts/expert_" + id.toString().substring(0, 8) + ".md";
        List<UUID> reportIds = getReportIdHistory(expert);
        saveSkillFileMeta(expert, promptPath, reportIds);
        expert.setStatus("active");
        expert.setGrainCount((int) expertGrainRepository.findByExpertId(id).size());
        expertSkillRepository.save(expert);;

        log.info("萃取师Skill已激活, expertId: {}, grainCount: {}", expertId, expert.getGrainCount());}


    /** 追加文件到已有萃取师（上传弹窗"选择已有"模式） */
    private ExpertSkillDetailResponse addDocumentsToExpert(String existingExpertId, List<Map<String, Object>> files) {
        addDocuments(existingExpertId, files);
        return getExpertDetail(existingExpertId);
    }

    /** 重试失败的萃取师 — 重置为 pending，scheduler 30s 内重新拾取 */
    @Transactional(rollbackFor = Exception.class)
    public void retryExpert(String expertId) {
        UUID id = UUID.fromString(expertId);
        ExpertSkill expert = expertSkillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "萃取师不存在"));
        if (!"failed".equals(expert.getStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "仅失败状态的萃取师可重试");
        }

        expert.setStatus("pending");
        expertSkillRepository.save(expert);
        log.info("萃取师已重置为pending, expertId={}", expertId);
    }


    /** 上传文档文件字节（multipart → 落盘 → 建 ExpertDocument → 触发重分析） */
    @Transactional(rollbackFor = Exception.class)
    public ExpertDocument uploadDocumentFile(UUID expertId, org.springframework.web.multipart.MultipartFile file) {
        ExpertSkill expert = expertSkillRepository.findById(expertId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "萃取师不存在"));
        LocalDateTime now = LocalDateTime.now();
        String basePath = storageBasePath != null && !storageBasePath.isBlank() ? storageBasePath : "data/files";
        String dir = basePath + "/experts/" + expertId + "/";
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "untitled";
        String safeName = System.currentTimeMillis() + "_" + originalName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        java.io.File destDir = new java.io.File(dir).getAbsoluteFile();
        { if (!destDir.exists()) destDir.mkdirs(); }
        java.io.File dest = new java.io.File(destDir, safeName);
        try {
            file.transferTo(dest);
        } catch (Exception e) {
            log.error("文件保存失败, expertId={}, path={}, originalName={}", expertId, dest.getAbsolutePath(), originalName, e);
            throw new RuntimeException("文件保存失败: " + e.getMessage());
        }

        ExpertDocument doc = ExpertDocument.builder()
                .id(UUID.randomUUID()).expertId(expertId)
                .fileName(originalName).fileUrl(dest.toPath().normalize().toAbsolutePath().toString())
                .fileType(file.getContentType()).fileSize(file.getSize())
                .status("uploaded").createdAt(now).build();
        doc = expertDocumentRepository.save(doc);
        // 重置状态为pending，触发调度器重新分析
        expert.setStatus("pending");
        expertSkillRepository.save(expert);
        log.info("文档文件已上传, expertId={}, docId={}, fileName={}, size={}",
                expertId, doc.getId(), originalName, file.getSize());
        return doc;
    }


    /** 追加文件——同样按时间戳分目录+同批次+跨批次同名编号 */
    @Transactional(rollbackFor = Exception.class)
    public void addDocuments(String expertId, List<Map<String, Object>> files) {
        UUID id = UUID.fromString(expertId);
        LocalDateTime now = LocalDateTime.now();
        String basePath = storageBasePath != null && !storageBasePath.isBlank() ? storageBasePath : "data/files";
        String baseDir = basePath + "/experts/" + id + "/";
        java.util.Set<String> batchNames = new java.util.HashSet<>();

        for (Map<String, Object> file : files) {
            String originalName = (String) file.get("fileName");
            String safeName = resolveFileName(baseDir, originalName, batchNames);

            ExpertDocument doc = ExpertDocument.builder().id(UUID.randomUUID()).expertId(id)
                    .fileName(originalName).fileUrl(baseDir + safeName)
                    .fileType((String) file.get("fileType")).status("uploaded").createdAt(now).build();
            expertDocumentRepository.save(doc);}

        // 重置状态为pending，触发调度器重新分析
        ExpertSkill expert = expertSkillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.EXPERT_NOT_FOUND));
        expert.setStatus("pending");
        expertSkillRepository.save(expert);;
        log.info("文件已追加并触发重新分析, expertId: {}, files: {}", expertId, files.size());}


    /** 删除文件 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(String expertId, String documentId) {
        UUID did = UUID.fromString(documentId);
        expertDocumentRepository.deleteById(did);
        // under_review颗粒→废弃
        List<ExpertGrain> toDeprecate = expertGrainRepository.findByExpertIdAndStatus(UUID.fromString(expertId), "under_review");
        toDeprecate.forEach(g -> g.setStatus("deprecated"));
        expertGrainRepository.saveAll(toDeprecate);
        log.info("文件已删除, documentId: {}", documentId);}


    /** 替换文件 */
    @Transactional(rollbackFor = Exception.class)
    public void replaceDocument(String expertId, String documentId, Map<String, Object> newFile) {
        deleteDocument(expertId, documentId);
        addDocuments(expertId, List.of(newFile));
        log.info("文件已替换, documentId: {}", documentId);}


    /** 重新生成综合Skill */
    @Transactional(readOnly = true)
    public void regenerateComposite() {
        log.info("开始重新生成综合Skill");

        // 加载所有 active 状态的萃取师
        List<ExpertSkill> activeExperts = expertSkillRepository.findByStatus("active");
        if (activeExperts.isEmpty()) {
            log.warn("没有已激活的萃取师，综合Skill生成跳过");
            return;
        }

        int totalGrains = 0;
        Set<String> consensusTags = new java.util.HashSet<>();
        Set<String> conflictTags = new java.util.HashSet<>();
        StringBuilder preview = new StringBuilder("# 综合Skill指令预览\n\n## 包含萃取师\n");

        for (ExpertSkill expert : activeExperts) {
            List<ExpertGrain> grains = expertGrainRepository.findByExpertId(expert.getId());
            long activeGrains = grains.stream().filter(g -> "active".equals(g.getStatus())).count();
            totalGrains += (int) activeGrains;
            preview.append("- ").append(expert.getName()).append("（")
                   .append(activeGrains).append("条活跃法则）\n");

            // 简单统计：按 category 分组，同 category 多条 = 共识
            Map<String, Long> catCounts = grains.stream()
                    .filter(g -> "active".equals(g.getStatus()))
                    .collect(java.util.stream.Collectors.groupingBy(
                            g -> g.getCategory() != null ? g.getCategory() : "未分类",
                            java.util.stream.Collectors.counting()));
            catCounts.forEach((cat, cnt) -> {
                if (cnt >= 2) {
                    consensusTags.add(cat);
                } else if (cnt == 1) {
                    conflictTags.add(cat);
                }
            });
        }

        preview.append("\n## 统计\n");
        preview.append("- 萃取师数：").append(activeExperts.size()).append("\n");
        preview.append("- 总活跃法则：").append(totalGrains).append("\n");
        preview.append("- 共识类别：").append(consensusTags.size()).append(" 个\n");
        preview.append("- 互补类别：").append(conflictTags.size()).append(" 个\n");

        log.info("综合Skill重新生成完成, experts: {}, grains: {}", activeExperts.size(), totalGrains);
    }


    /** 获取综合Skill详情 */
    /**
     * 更新文档内容（手动处理图片/音频后填入）
     */
    public void updateDocumentContent(String docId, String parsedContent, String status) {
        UUID id = UUID.fromString(docId);
        ExpertDocument doc = expertDocumentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.DOCUMENT_NOT_FOUND));
        if (parsedContent != null) {
            doc.setParsedContent(parsedContent);
        }

        if (status != null) {
            doc.setStatus(status);
        }

        expertDocumentRepository.save(doc);
        log.info("文档内容已更新, docId: {}, status: {}", docId, status);
    }


    public ExpertCompositeResponse getCompositeDetail() {
        List<ExpertSkill> activeExperts = expertSkillRepository.findByStatus("active");
        int expertCount = activeExperts.size();

        // 批量查所有激活萃取师的颗粒（避免 N+1）
        List<UUID> expertIds = activeExperts.stream().map(ExpertSkill::getId).toList();
        Map<UUID, List<ExpertGrain>> grainsByExpert = expertGrainRepository
                .findByExpertIdInAndStatus(expertIds, "active").stream()
                .collect(java.util.stream.Collectors.groupingBy(ExpertGrain::getExpertId));

        int consensusCount = 0;
        int singleCount = 0;
        int conflictCount = 0;

        for (ExpertSkill expert : activeExperts) {
            List<ExpertGrain> grains = grainsByExpert.getOrDefault(expert.getId(), List.of());
            for (ExpertGrain g : grains) {
                if ("consensus".equals(g.getConsensusType())) { consensusCount++; }
                else if ("conflict".equals(g.getConsensusType())) { conflictCount++; }
                else { singleCount++; }
            }
        }

        // 版本号基于激活萃取师数量
        String version = "V" + expertCount;

        // 构建预览
        StringBuilder preview = new StringBuilder();
        preview.append("# 综合Skill指令预览\n\n");
        preview.append("## 包含萃取师\n");
        for (ExpertSkill e : activeExperts) {
            preview.append("- ").append(e.getName());
            if (e.getStyleTags() != null) preview.append("（").append(e.getStyleTags()).append("）");
            preview.append("\n");
        }

        preview.append("\n## 统计\n");
        preview.append("- 共识经验：").append(consensusCount).append("条\n");
        preview.append("- 独家经验：").append(singleCount).append("条\n");
        if (conflictCount > 0) {
            preview.append("- 矛盾项：").append(conflictCount).append("条\n");
        }

        return ExpertCompositeResponse.builder()
                .version(version)
                .expertCount(expertCount)
                .consensusCount(consensusCount)
                .singleCount(singleCount)
                .conflictCount(conflictCount)
                .updatedAt(LocalDateTime.now().toString())
                .contentPreview(preview.toString())
                .build();
    }

    // ===== Helper methods =====
    private ExpertSkillListResponse toListResponse(ExpertSkill e,
            Map<UUID, Long> docCounts, Map<UUID, Long> grainCounts) {
        int docCount = docCounts.getOrDefault(e.getId(), 0L).intValue();
        int grainCount = grainCounts.getOrDefault(e.getId(), 0L).intValue();
        return ExpertSkillListResponse.builder().id(e.getId().toString()).name(e.getName())
                .description(e.getDescription()).sourceType(e.getSourceType())
                .domain(e.getDomain())
                .styleTags(parseJsonList(e.getStyleTags())).industryTags(parseJsonList(e.getIndustryTags()))
                .seniority(e.getSeniority()).grainCount(grainCount).status(e.getStatus())
                .documentCount(docCount)
                .createdAt(toString(e.getCreatedAt())).build();
    }


    private List<String> parseJsonList(String json) {
        { if (json == null || json.isEmpty()) return List.of(); }
        try { return objectMapper.readValue(json, List.class); } catch (Exception e) { log.warn("JSON解析失败", e); return List.of(); }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { log.warn("JSON序列化失败", e); return "[]"; }
    }

    private String toString(LocalDateTime dt) { return dt != null ? dt.toString() : null; }

    /**
     * 将 AI 原始输出重新组织为六章 JSON 结构
     */
    private String structureExpertReport(String rawContent, String expertName, String domain) {
        // 尝试调用 AI 二次结构化
        try {
            String systemPrompt = promptLoader.format("expert_structure_report.md", Map.of(
                    "raw_content", rawContent.substring(0, Math.min(rawContent.length(), 2000))
            ), domain);
            String json = chatClient.prompt()
                    .system(systemPrompt)
                    .user("整理以上萃取师经验，输出六章JSON")
                    .call()
                    .content();
            if (json != null && !json.isEmpty()) {
                json = json.trim();
                // 去掉 markdown 代码块
                { if (json.startsWith("```")) json = json.replaceAll("```json\\s*|```\\s*", "").trim(); }
                if (json.startsWith("{")) {
                    // 验证JSON合法性
                    try {
                        objectMapper.readTree(json);
                        return json;
                    } catch (Exception e) {
                        log.warn("AI结构化报告JSON格式无效，将使用降级格式: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("结构化萃取师报告失败，使用降级格式: {}", e.getMessage());
        }

        // AI结构化失败时抛出异常，不返回虚假数据
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "AI结构化萃取师报告失败，请稍后重试");
    }


    private String toJsonString(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f") + "\"";
    }

    /**
     * 解析文件名——同批次同名自动编号，同时检查磁盘已有文件
     */
    private String resolveFileName(String baseDir, String originalName, java.util.Set<String> batchNames) {
        int dotIdx = originalName.lastIndexOf('.');
        String baseName = dotIdx > 0 ? originalName.substring(0, dotIdx) : originalName;
        String ext = dotIdx > 0 ? originalName.substring(dotIdx) : "";

        String candidate = originalName;
        int counter = 1;
        java.io.File dir = new java.io.File(baseDir);

        // 循环直到：磁盘上没有 + 本批次内没重复
        while ((dir.exists() && new java.io.File(baseDir + candidate).exists())
                || batchNames.contains(candidate)) {
            candidate = baseName + "_" + counter + ext;
            counter++;
        }

        batchNames.add(candidate);
        return candidate;
    }


    /**
     * 共享的 AI 报告生成调用：构建 prompt → 调 AI → 返回原始内容。
     * document 和 interview 两个来源共用，消除重复代码。
     */
    private String callAiForExpertReport(ExpertSkill expert, String fileContentsText,
                                          String userMessage) {
        String domain = resolveExpertDomain(expert);
        String systemPrompt = promptLoader.format("expert_document_extraction.md", Map.of(
                "expert_name", expert.getName() != null ? expert.getName() : "",
                "expert_description", expert.getDescription() != null ? expert.getDescription() : "",
                "expert_seniority", expert.getSeniority() != null ? expert.getSeniority() : "",
                "expert_style", expert.getStyleTags() != null ? expert.getStyleTags() : "",
                "expert_industry", expert.getIndustryTags() != null ? expert.getIndustryTags() : "",
                "file_contents", fileContentsText
        ), domain);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
    }

    /**
     * 处理 interview 来源的 ExpertSkill：直接使用 sourceContent 作为分析素材。
     * 跳过文件解析步骤，直接送入 AI 分析管道。
     */
    private void processInterviewSource(ExpertSkill expert, String transcript) {
        log.info("处理元访谈来源 ExpertSkill, expertId={}", expert.getId());

        // 转录截断保护：deepseek-chat 64K context，三段采样（头+中+尾）
        // 上限 40000 字（~10K tokens），远低于 64K，保留充足的 prompt + response 空间
        String safeTranscript = sampleTranscript(transcript, 40000);

        String rawContent = callAiForExpertReport(expert,
                "=== 元访谈转录 ===\n" + safeTranscript,
                "请分析以上元访谈转录，输出完整的《萃取师经验报告》JSON");

        if (rawContent != null && rawContent.length() > 10) {
            String domain = resolveExpertDomain(expert);
            String structuredJson = structureExpertReport(rawContent, expert.getName(), domain);
            Report report = Report.builder()
                    .id(UUID.randomUUID())
                    .title(expert.getName() + " · 萃取师经验报告")
                    .contentJson(structuredJson != null ? structuredJson : rawContent)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            reportRepository.save(report);
            expert.setReportId(report.getId());
        }

        expert.setStatus("extracting");
        expertSkillRepository.save(expert);;
        // 自动触发颗粒提取
        try {
            self.extractGrains(expert.getId().toString());
        } catch (Exception e) {
            log.warn("元访谈自动提取颗粒失败, expertId={}, 可稍后手动提取", expert.getId(), e);
        }

        log.info("元访谈 ExpertSkill AI分析完成, expertId={}", expert.getId());
    }

    /**
     * 三段采样：头+中+尾，与 MaterialCleaningService.sampleText() 同策略。
     * 避免只截头部丢失后段信息，也避免头尾两段丢失中间核心内容。
     */
    private String sampleTranscript(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text != null ? text : "";
        int partLen = maxLen / 3;
        int headEnd = partLen;
        int midStart = Math.max(headEnd, text.length() / 2 - partLen / 2);
        int midEnd = Math.min(midStart + partLen, text.length());
        int tailStart = Math.max(midEnd, text.length() - partLen);
        return text.substring(0, headEnd)
                + "\n\n...[中间省略]...\n\n"
                + text.substring(midStart, midEnd)
                + "\n\n...[中间省略]...\n\n"
                + text.substring(tailStart);
    }

    /** 解析 ExpertSkill 的 domain，为 null 时抛异常暴露 bug */
    private String resolveExpertDomain(ExpertSkill expert) {
        if (expert.getDomain() == null || expert.getDomain().isBlank()) {
            throw new IllegalStateException(
                "ExpertSkill.domain 不应为空，expertId=" + expert.getId());
        }

        return expert.getDomain();
    }

}

