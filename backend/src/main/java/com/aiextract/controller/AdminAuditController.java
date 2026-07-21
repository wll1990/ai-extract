package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.SseAdapter;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.*;
import com.aiextract.repository.*;
import com.aiextract.service.PracticeDemoService;
import com.aiextract.service.ExtractionReportService;
import com.aiextract.service.SkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminAuditController {

    private final SkillRepository skillRepository;
    private final SkillProfileRepository profileRepository;
    private final SkillMaterialRepository materialRepository;
    private final SkillEvaluationRepository evaluationRepository;
    private final com.aiextract.repository.ReportHistoryRepository historyRepository;
    private final com.aiextract.config.DomainConfigLoader domainConfigLoader;
    private final ExtractionReportService extractionReportService;
    private final ExperienceGrainRepository grainRepository;
    private final PracticeDemoService practiceDemoService;
    private final SkillService skillService;
    private final ObjectMapper objectMapper;

    /** 审核仪表盘 — 聚合返回分身发布前的所有关键数据 */
    @GetMapping("/admin/skills/{skillId}/audit-dashboard")
    public ApiResponse<Map<String, Object>> getAuditDashboard(@PathVariable UUID skillId) {
        Skill skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        Map<String, Object> data = new LinkedHashMap<>();

        // skill 基本信息
        Map<String, Object> skillMap = new LinkedHashMap<>();
        skillMap.put("id", skill.getId().toString());
        skillMap.put("status", skill.getStatus());
        skillMap.put("displayName", skill.getDisplayName());
        skillMap.put("ownerName", skill.getOwnerName());
        skillMap.put("ownerTitle", skill.getOwnerTitle());
        skillMap.put("department", skill.getDepartment());
        skillMap.put("seniority", skill.getSeniority());
        skillMap.put("tags", skill.getTags());
        skillMap.put("targetScenarios", skill.getTargetScenarios());
        skillMap.put("limitations", skill.getLimitations());
        skillMap.put("publishNotes", skill.getPublishNotes());
        skillMap.put("openingMessage", skill.getOpeningMessage());
        skillMap.put("createdAt", skill.getCreatedAt() != null ? skill.getCreatedAt().toString() : null);
        data.put("skill", skillMap);

        // 画像
        profileRepository.findBySkillId(skillId).ifPresent(profile -> {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("personality", profile.getPersonality());
            pm.put("speakingStyle", profile.getSpeakingStyle());
            pm.put("background", profile.getBackground());
            pm.put("commonPhrases", profile.getCommonPhrases());
            pm.put("knowledgeDomains", profile.getKnowledgeDomains());
            pm.put("communicationPreferences", profile.getCommunicationPreferences());
            pm.put("weaknessNotes", profile.getWeaknessNotes());
            pm.put("extraContext", profile.getExtraContext());
            data.put("profile", pm);
        });

        // 技能摘要
        List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalGrains", grains.size());
        summary.put("activeGrains", grains.stream().filter(g -> "active".equals(g.getStatus())).count());
        summary.put("deprecatedGrains", grains.stream().filter(g -> "deprecated".equals(g.getStatus())).count());

        Map<String, Long> sceneCoverage = grains.stream()
            .filter(g -> g.getSceneTag() != null)
            .collect(Collectors.groupingBy(ExperienceGrain::getSceneTag, Collectors.counting()));
        summary.put("sceneTags", new ArrayList<>(sceneCoverage.keySet()));
        summary.put("sceneCoverage", sceneCoverage);

        double avgWeight = grains.stream()
            .filter(g -> g.getWeight() != null)
            .mapToDouble(ExperienceGrain::getWeight).average().orElse(1.0);
        summary.put("avgWeight", Math.round(avgWeight * 100.0) / 100.0);

        // 五维均分 + 质量分布
        double sumS = 0, sumR = 0, sumC = 0, sumD = 0, sumF = 0;
        int scoredCount = 0;
        long highQ = 0, midQ = 0, lowQ = 0;
        for (ExperienceGrain g : grains) {
            if (g.getQualityScore() != null) {
                double qs = g.getQualityScore();
                if (qs >= 4.0) {

                    highQ++;

                }
                else if (qs >= 3.0) midQ++;
                else lowQ++;
            }
            if (g.getVerificationNotes() != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> vn = objectMapper.readValue(g.getVerificationNotes(), Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> scores = (Map<String, Object>) vn.get("scores");
                    if (scores != null) {
                        sumS += ((Number) scores.getOrDefault("specificity", 0)).doubleValue();
                        sumR += ((Number) scores.getOrDefault("reproducibility", 0)).doubleValue();
                        sumC += ((Number) scores.getOrDefault("causality", 0)).doubleValue();
                        sumD += ((Number) scores.getOrDefault("distinctiveness", 0)).doubleValue();
                        sumF += ((Number) scores.getOrDefault("falsifiability", 0)).doubleValue();
                        scoredCount++;
                    }
                } catch (Exception e) { log.debug("解析数据项失败", e); }
            }
        }
        if (scoredCount > 0) {
            summary.put("dimensionAvg", Map.of(
                "specificity", Math.round(sumS / scoredCount * 10.0) / 10.0,
                "reproducibility", Math.round(sumR / scoredCount * 10.0) / 10.0,
                "causality", Math.round(sumC / scoredCount * 10.0) / 10.0,
                "distinctiveness", Math.round(sumD / scoredCount * 10.0) / 10.0,
                "falsifiability", Math.round(sumF / scoredCount * 10.0) / 10.0));
        }
        summary.put("qualityDistribution", Map.of("high", highQ, "mid", midQ, "low", lowQ));
        data.put("skillsSummary", summary);

        // 场景颗粒分组（按 sceneTag，供审核页"技能场景化"步骤展示）
        Map<String, List<Map<String, Object>>> scenarioGrains = new LinkedHashMap<>();
        for (ExperienceGrain g : grains) {
            if (g.getSceneTag() == null || !"active".equals(g.getStatus())) continue;
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id", g.getId().toString());
            gm.put("sceneTag", g.getSceneTag());
            gm.put("sceneDescription", g.getSceneDescription());
            gm.put("expertThought", g.getExpertThought());
            gm.put("standardScript", g.getStandardScript());
            gm.put("commonMistakes", g.getCommonMistakes());
            gm.put("applicableCondition", g.getApplicableCondition());
            gm.put("qualityScore", g.getQualityScore());
            gm.put("difficultyLevel", g.getDifficultyLevel());
            scenarioGrains.computeIfAbsent(g.getSceneTag(), k -> new ArrayList<>()).add(gm);
        }
        data.put("scenarioGrains", scenarioGrains);

        // 素材
        List<Map<String, Object>> materials = materialRepository.findBySkillId(skillId).stream()
            .map(m -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("id", m.getId().toString());
                mm.put("fileName", m.getFileName());
                mm.put("fileType", m.getFileType());
                mm.put("fileSize", m.getFileSize());
                mm.put("version", m.getVersion());
                mm.put("status", m.getStatus());
                mm.put("analysisNotes", m.getAnalysisNotes());
                mm.put("createdAt", m.getCreatedAt().toString());
                try {
                    if (m.getExtractionMetadata() != null) {
                        Map<String, Object> meta = objectMapper.readValue(m.getExtractionMetadata(), Map.class);
                        mm.put("reportVersion", meta.getOrDefault("reportVersion", ""));
                        if (meta.containsKey("context")) mm.put("context", meta.get("context"));
                        if (meta.containsKey("patterns")) mm.put("patterns", meta.get("patterns"));
                        if (meta.containsKey("faq")) mm.put("faq", meta.get("faq"));
                        if (meta.containsKey("narrative")) mm.put("narrative", meta.get("narrative"));
                        if (meta.containsKey("verifiedCount")) mm.put("verifiedCount", meta.get("verifiedCount"));
                        if (meta.containsKey("rejectedCount")) mm.put("rejectedCount", meta.get("rejectedCount"));
                    }
                } catch (Exception e) { log.debug("解析数据项失败", e); }
                return mm;
            }).collect(Collectors.toList());
        data.put("materials", materials);

        // 萃取结果摘要（供审核页直接展示）
        Map<String, Object> extractionResult = new LinkedHashMap<>();
        for (Map<String, Object> m : materials) {
            if (m.containsKey("patterns")) {
                extractionResult.put("context", m.get("context"));
                extractionResult.put("patterns", m.get("patterns"));
                extractionResult.put("faq", m.get("faq"));
                extractionResult.put("narrative", m.get("narrative"));
                extractionResult.put("verifiedCount", m.get("verifiedCount"));
                extractionResult.put("rejectedCount", m.get("rejectedCount"));
                break;
            }
        }
        extractionResult.put("grainCount", grains.size());
        data.put("extractionResult", extractionResult);

        // 评分
        List<Map<String, Object>> evals = evaluationRepository.findBySkillIdOrderByCreatedAtDesc(skillId)
            .stream().map(e -> {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("id", e.getId().toString());
                em.put("mode", e.getMode());
                em.put("score", e.getScore());
                em.put("styleScore", e.getStyleScore());
                em.put("consistencyScore", e.getConsistencyScore());
                em.put("behaviorScore", e.getBehaviorScore());
                em.put("scriptReuseScore", e.getScriptReuseScore());
                em.put("strengths", e.getStrengths());
                em.put("improvements", e.getImprovements());
                em.put("createdAt", e.getCreatedAt().toString());
                return em;
            }).collect(Collectors.toList());
        data.put("evaluations", evals);

        return ApiResponse.success(data);
    }

    /** 补充信息录入 */
    @GetMapping("/admin/skills/{skillId}/supplement")
    public ApiResponse<Map<String, Object>> getSupplement(@PathVariable UUID skillId) {
        Skill skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        SkillProfile profile = profileRepository.findBySkillId(skillId).orElse(null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("displayName", skill.getDisplayName());
        data.put("ownerName", skill.getOwnerName());
        data.put("ownerTitle", skill.getOwnerTitle());
        data.put("department", skill.getDepartment());
        data.put("seniority", skill.getSeniority());
        data.put("tags", skill.getTags());
        data.put("targetScenarios", skill.getTargetScenarios());
        data.put("limitations", skill.getLimitations());
        data.put("publishNotes", skill.getPublishNotes());
        data.put("openingMessage", skill.getOpeningMessage());
        if (profile != null) {
            data.put("communicationPreferences", profile.getCommunicationPreferences());
            data.put("weaknessNotes", profile.getWeaknessNotes());
        }
        return ApiResponse.success(data);
    }

    @PutMapping("/admin/skills/{skillId}/supplement")
    public ApiResponse<Void> saveSupplement(@PathVariable UUID skillId, @RequestBody Map<String, Object> body) {
        Skill skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404, "分身不存在"));

        if (body.containsKey("displayName")) skill.setDisplayName((String) body.get("displayName"));
        if (body.containsKey("ownerName")) skill.setOwnerName((String) body.get("ownerName"));
        if (body.containsKey("ownerTitle")) skill.setOwnerTitle((String) body.get("ownerTitle"));
        if (body.containsKey("department")) skill.setDepartment((String) body.get("department"));
        if (body.containsKey("seniority")) skill.setSeniority((String) body.get("seniority"));
        if (body.containsKey("tags")) skill.setTags((String) body.get("tags"));
        if (body.containsKey("targetScenarios")) skill.setTargetScenarios((String) body.get("targetScenarios"));
        if (body.containsKey("limitations")) skill.setLimitations((String) body.get("limitations"));
        if (body.containsKey("publishNotes")) skill.setPublishNotes((String) body.get("publishNotes"));
        if (body.containsKey("openingMessage")) skill.setOpeningMessage((String) body.get("openingMessage"));
        skillRepository.save(skill);

        if (body.containsKey("communicationPreferences") || body.containsKey("weaknessNotes")) {
            SkillProfile profile = profileRepository.findBySkillId(skillId)
                .orElse(SkillProfile.builder().skillId(skillId).build());
            if (body.containsKey("communicationPreferences"))
                profile.setCommunicationPreferences((String) body.get("communicationPreferences"));
            if (body.containsKey("weaknessNotes"))
                profile.setWeaknessNotes((String) body.get("weaknessNotes"));
            profileRepository.save(profile);
        }

        return ApiResponse.success();
    }

    /** 发布 / 废弃（含后端校验） */
    /** 萃取报告 HTML（支持 ?download=true 触发下载） */
    /** 报告历史版本列表 */
    @GetMapping("/admin/skills/{skillId}/report-history")
    public ApiResponse<List<Map<String, Object>>> getReportHistory(@PathVariable UUID skillId) {
        List<com.aiextract.model.ReportHistory> list = historyRepository.findBySkillIdOrderByGeneratedAtDesc(skillId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var h : list) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", h.getId().toString());
            item.put("version", h.getVersion());
            item.put("generatedAt", h.getGeneratedAt().toString());
            item.put("grainCount", h.getGrainCount());
            item.put("materialIds", h.getMaterialIds());
            result.add(item);
        }
        return ApiResponse.success(result);
    }

    /** 触发报告生成的最小活跃颗粒数（配置：app.report.min-grains） */
    @Value("${app.report.min-grains:10}")
    private int reportMinGrains;
    /** 触发报告生成的最小场景覆盖数（配置：app.report.min-scenes） */
    @Value("${app.report.min-scenes:3}")
    private int reportMinScenes;

    /** 报告就绪检查 — 前端按钮据此显示状态 */
    @GetMapping("/admin/skills/{skillId}/report-readiness")
    public ApiResponse<Map<String, Object>> getReportReadiness(@PathVariable UUID skillId) {
        Skill skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        UUID spaceId = skill.getSpaceId();
        long grains = grainRepository.countBySpaceIdAndStatus(spaceId, "active");
        long scenes = grainRepository.countDistinctSceneTagsBySpaceIdAndStatus(spaceId, "active");
        boolean ready = grains >= reportMinGrains && scenes >= reportMinScenes;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", ready);
        result.put("grains", grains);
        result.put("scenes", scenes);
        result.put("needGrains", Math.max(0, reportMinGrains - grains));
        result.put("needScenes", Math.max(0, reportMinScenes - scenes));
        return ApiResponse.success(result);
    }

    @GetMapping(value = "/admin/skills/{skillId}/report", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String getExtractionReport(@PathVariable UUID skillId,
            @RequestParam(defaultValue = "false") boolean download,
            jakarta.servlet.http.HttpServletResponse response) {
        String html = extractionReportService.generateHtml(skillId);
        if (download) {
            response.setHeader("Content-Disposition",
                    "attachment; filename=extraction-report-" + skillId + ".html");
        }
        return html;
    }

    /** 萃取报告 PPT 下载 */
    @GetMapping("/admin/skills/{skillId}/report-ppt")
    public void getExtractionReportPpt(@PathVariable UUID skillId,
            jakarta.servlet.http.HttpServletResponse response) {
        try {
            byte[] ppt = extractionReportService.generatePpt(skillId);
            if (ppt == null) {
                response.sendError(404, "报告数据未就绪");
                return;
            }
            response.setContentType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
            response.setHeader("Content-Disposition",
                    "attachment; filename=extraction-report-" + skillId + ".pptx");
            response.getOutputStream().write(ppt);
        } catch (Exception e) {
            response.setStatus(500);
        }
    }

    @PutMapping("/admin/skills/{skillId}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable UUID skillId, @RequestBody Map<String, String> body) {
        Skill skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        String action = body.getOrDefault("action", "publish");

        if ("publish".equals(action)) {
            // 发布前校验（与前端 publishChecks 保持一致）
            List<String> blockers = new ArrayList<>();

            // 1) 活跃颗粒 ≥ 10
            List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId());
            long activeGrains = grains.stream().filter(g -> "active".equals(g.getStatus())).count();
            if (activeGrains < 10) {
                blockers.add("活跃颗粒不足（当前" + activeGrains + "条，需 ≥ 10）");
            }

            // 2) 场景覆盖 ≥ 3
            long sceneCount = grains.stream()
                .filter(g -> g.getSceneTag() != null && !g.getSceneTag().isBlank())
                .map(ExperienceGrain::getSceneTag).distinct().count();
            if (sceneCount < 3) {
                blockers.add("场景覆盖不足（当前" + sceneCount + "个，需 ≥ 3）");
            }

            // 3) 画像已填写：姓名 + 性格 + 背景 + 知识领域
            if (skill.getOwnerName() == null || skill.getOwnerName().isBlank()) {
                blockers.add("画像未完善：销冠姓名为必填");
            }
            SkillProfile profile = profileRepository.findBySkillId(skillId).orElse(null);
            if (profile == null
                || profile.getPersonality() == null || profile.getPersonality().isBlank()
                || profile.getBackground() == null || profile.getBackground().isBlank()
                || profile.getKnowledgeDomains() == null || profile.getKnowledgeDomains().isBlank()
                || "[]".equals(profile.getKnowledgeDomains())) {
                blockers.add("画像未完善：性格特征、从业背景、擅长领域为必填");
            }

            if (!blockers.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("passed", false);
                result.put("blockers", blockers);
                return ApiResponse.success(result);
            }

            skill.setStatus("published");
            skill.setPublishedAt(java.time.LocalDateTime.now());
            skillRepository.save(skill);
            // 异步生成开场白（若已手动填写则跳过）
            skillService.generateOpeningMessage(skillId);
        } else if ("unpublish".equals(action)) {
            if (!"published".equals(skill.getStatus())) {
                throw new BusinessException(400, "仅已发布的分身可以撤销发布");
            }
            skill.setStatus("reviewing");
            skill.setPublishedAt(null);
            skillRepository.save(skill);
        } else if ("discard".equals(action)) {
            skill.setStatus("discarded");
            skillRepository.save(skill);
        }
        return ApiResponse.success(Map.of("passed", true));
    }

    /** 场景对练 SSE 流式 */
    @PostMapping(value = "/admin/skills/{skillId}/practice-scenario", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter practiceScenario(@PathVariable UUID skillId,
            @RequestBody Map<String, Object> body) {
        String grainId = (String) body.get("grainId");
        String mode = (String) body.getOrDefault("mode", "with_skill");
        String customerMessage = (String) body.get("customerMessage");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) body.getOrDefault("history", List.of());
        String domain = skillRepository.findById(skillId)
                .map(s -> domainConfigLoader.resolveDomain(s))
                .orElse("sales.b2b_enterprise");
        return SseAdapter.fromFlux(practiceDemoService.simulateScenario(skillId, grainId, mode, customerMessage, history, domain));
    }

    /** 颗粒溯源 */
    @GetMapping("/admin/skills/{skillId}/grain-source")
    public ApiResponse<Map<String, Object>> getGrainSource(
            @PathVariable UUID skillId, @RequestParam UUID grainId) {
        ExperienceGrain grain = grainRepository.findById(grainId).orElse(null);
        if (grain == null || grain.getSourceMaterialId() == null)
            return ApiResponse.success(Map.of());

        SkillMaterial material = materialRepository.findById(grain.getSourceMaterialId()).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("materialFileName", material != null ? material.getFileName() : "未知");
        result.put("extractedAt", grain.getCreatedAt() != null ? grain.getCreatedAt().toString() : "");
        result.put("qualityScore", grain.getQualityScore());

        // 从原文中截取上下文（多关键词尝试匹配）
        if (material != null && material.getParsedContent() != null) {
            String raw = material.getParsedContent();
            String[] keywords = {
                grain.getStandardScript(), grain.getSceneDescription(), grain.getExpertThought()
            };
            for (String kw : keywords) {
                if (kw == null || kw.length() < 8) continue;
                for (int len = Math.min(20, kw.length()); len >= 10; len -= 5) {
                    String fragment = kw.substring(0, len);
                    if (raw.contains(fragment)) {
                        int idx = raw.indexOf(fragment);
                        int start = Math.max(0, idx - 100);
                        int end = Math.min(raw.length(), idx + fragment.length() + 100);
                        result.put("sourceExcerpt", raw.substring(start, end));
                        break;
                    }
                }
                if (result.containsKey("sourceExcerpt")) break;
            }
            // fallback: 返回原文中间一段
            if (!result.containsKey("sourceExcerpt") && raw.length() > 200) {
                int mid = raw.length() / 2;
                result.put("sourceExcerpt", raw.substring(Math.max(0, mid - 100), Math.min(raw.length(), mid + 100)));
            }
        }
        return ApiResponse.success(result);
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** AI 模拟客户自动对练 SSE 流式 */
    @PostMapping(value = "/admin/skills/{skillId}/auto-demo", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter autoDemo(@PathVariable UUID skillId,
            @RequestBody(required = false) Map<String, String> body) {
        String mode = body != null ? body.getOrDefault("mode", "full") : "full";
        return SseAdapter.fromFlux(practiceDemoService.autoDemo(skillId, mode));
    }

    /** AI 评分 — 委托 PracticeDemoService */
    @PostMapping("/admin/skills/{skillId}/evaluate-demo")
    public ApiResponse<Map<String, Object>> evaluateDemo(
            @PathVariable UUID skillId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        if (messages == null || messages.isEmpty()) {
            return ApiResponse.error(400, "对话记录不能为空");
        }
        try {
            Map<String, Object> result = practiceDemoService.evaluateDemo(skillId, messages);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("evaluate-demo error", e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /** 颗粒匹配 — 问答/对练结束后查询溯源信息 */
    @PostMapping("/admin/skills/{skillId}/match-grains")
    public ApiResponse<Map<String, Object>> matchGrains(
            @PathVariable UUID skillId,
            @RequestBody Map<String, String> body) {
        String query = body.get("query");
        if (query == null || query.isBlank()) {
            return ApiResponse.error(400, "查询内容不能为空");
        }
        try {
            Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, "分身不存在"));
            List<ExperienceGrain> allGrains = grainRepository.findBySpaceId(skill.getSpaceId());
            PracticeDemoService.GrainMatch match = practiceDemoService.matchGrains(query, skill.getSpaceId(), allGrains);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("matchLevel", match.level.name());

            List<Map<String, Object>> traces = new ArrayList<>();
            for (ExperienceGrain g : match.grains) {
                Map<String, Object> trace = new LinkedHashMap<>();
                trace.put("sceneTag", g.getSceneTag() != null ? g.getSceneTag() : "通用");
                trace.put("qualityScore", g.getQualityScore());
                trace.put("matchLevel", match.level.name());
                if (g.getSourceMaterialId() != null) {
                    materialRepository.findById(g.getSourceMaterialId()).ifPresent(m ->
                        trace.put("fileName", m.getFileName()));
                }
                traces.add(trace);
            }
            result.put("grains", traces);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("match-grains error", e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /** 对练 — 销冠标准答案对比 */
    @PostMapping("/admin/skills/{skillId}/practice-evaluate")
    public ApiResponse<Map<String, Object>> practiceEvaluate(
            @PathVariable UUID skillId,
            @RequestBody Map<String, Object> body) {
        String sceneTag = (String) body.getOrDefault("sceneTag", "");
        String customerMessage = (String) body.getOrDefault("customerMessage", "");
        String myResponse = (String) body.getOrDefault("myResponse", "");
        String previousChampionAnswer = (String) body.getOrDefault("previousChampionAnswer", "");
        int retryCount = body.containsKey("retryCount") ? ((Number) body.get("retryCount")).intValue() : 0;
        if (customerMessage.isBlank() || myResponse.isBlank()) {
            return ApiResponse.error(400, "客户消息和你的回答不能为空");
        }
        try {
            return ApiResponse.success(
                practiceDemoService.evaluatePracticeResponse(skillId, sceneTag, customerMessage, myResponse, previousChampionAnswer, retryCount));
        } catch (Exception e) {
            log.error("practice-evaluate error", e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /** 对练 — 多轮评分 */
    @PostMapping("/admin/skills/{skillId}/practice-score")
    public ApiResponse<Map<String, Object>> practiceScore(
            @PathVariable UUID skillId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rounds = (List<Map<String, Object>>) body.getOrDefault("rounds", List.of());
        if (rounds.isEmpty()) {
            return ApiResponse.error(400, "对练轮次为空");
        }
        try {
            return ApiResponse.success(practiceDemoService.scorePractice(skillId, rounds));
        } catch (Exception e) {
            log.error("practice-score error", e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /** 问答 — 知识点覆盖总结 */
    @PostMapping("/admin/skills/{skillId}/qa-summary")
    public ApiResponse<Map<String, Object>> qaSummary(
            @PathVariable UUID skillId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rounds = (List<Map<String, Object>>) body.getOrDefault("rounds", List.of());
        try {
            return ApiResponse.success(practiceDemoService.summarizeQa(skillId, rounds));
        } catch (Exception e) {
            log.error("qa-summary error", e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /** 对练 — AI 生成客户开场白 */
    @PostMapping("/admin/skills/{skillId}/practice-opening")
    public ApiResponse<String> practiceOpening(
            @PathVariable UUID skillId, @RequestBody Map<String, String> body) {
        String sceneTag = body.getOrDefault("sceneTag", "");
        try {
            return ApiResponse.success(practiceDemoService.generateCustomerOpening(skillId, sceneTag));
        } catch (Exception e) {
            log.error("practice-opening error", e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /** 对练 — 获取场景练习角度列表 */
    @GetMapping("/admin/skills/{skillId}/practice-angles")
    public ApiResponse<List<String>> practiceAngles(
            @PathVariable UUID skillId,
            @RequestParam String sceneTag) {
        try {
            List<String> angles = practiceDemoService.getScenePracticeAngles(skillId, sceneTag);
            return ApiResponse.success(angles);
        } catch (Exception e) {
            log.error("practice-angles error", e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    private String nn(String s) { return s != null ? s : ""; }
}
