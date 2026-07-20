package com.aiextract.service;

import com.aiextract.config.PromptLoader;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.ReportHistory;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillMaterial;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.ReportHistoryRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.aiextract.repository.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;

import java.io.StringWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionReportService {

    private final SkillRepository skillRepository;
    private final SkillMaterialRepository materialRepository;
    private final ExperienceGrainRepository grainRepository;
    private final ReportHistoryRepository historyRepository;
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final com.aiextract.config.DomainConfigLoader domainConfigLoader;
    private final ObjectMapper objectMapper;
    private final Configuration freemarkerConfig;
    private final ExtractionPptService pptService;

    public String generateHtml(UUID skillId) {
        Map<String, Object> model = buildReportModel(skillId);
        if (model == null) {

            return null;

        }

        Boolean isEmpty = (Boolean) model.getOrDefault("empty", false);
        if (Boolean.TRUE.equals(isEmpty)) {
            return renderEmptyPage((String) model.get("ownerName"), (String) model.get("statusMsg"));
        }

        try {
            Template tpl = freemarkerConfig.getTemplate("extraction-report.ftl");
            String html = FreeMarkerTemplateUtils.processTemplateIntoString(tpl, model);

            // 管理员主动生成报告时才记录正式版本
            saveReportHistory(skillId, (int) model.getOrDefault("grainCount", 0));

            return html;
        } catch (Exception e) {
            log.error("FreeMarker 渲染失败", e);
            return "<html><body><h1>报告渲染失败</h1><p>" + e.getMessage() + "</p></body></html>";
        }
    }

    public byte[] generatePpt(UUID skillId) {
        Map<String, Object> model = buildReportModel(skillId);
        { if (model == null || Boolean.TRUE.equals(model.getOrDefault("empty", false))) return null; }

        Skill skill = skillRepository.findById(skillId).orElse(null);
        String ownerName = skill != null ? getOwnerName(skill) : "未命名";
        List<ExperienceGrain> grains = grainRepository.findBySpaceId(
                skill != null ? skill.getSpaceId() : null);

        return pptService.generate(grains, model, ownerName);
    }

    private Map<String, Object> buildReportModel(UUID skillId) {
        Skill skill = skillRepository.findById(skillId).orElseThrow(() -> new RuntimeException("分身不存在"));
        List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId());
        List<SkillMaterial> materials = materialRepository.findBySkillId(skillId);

        boolean hasExtracted = materials.stream().anyMatch(m -> "extracted".equals(m.getStatus()));
        if (!hasExtracted || grains.isEmpty()) {
            String statusMsg = buildStatusMsg(materials);
            return Map.of("empty", true, "ownerName", getOwnerName(skill), "statusMsg", statusMsg);
        }

        // 取元数据
        String rawText = "";
        Map<String, Object> extractionMeta = null;
        for (SkillMaterial m : materials) {
            if (m.getParsedContent() != null && rawText.isEmpty()) {
                rawText = m.getParsedContent();
            }
            if (m.getExtractionMetadata() != null && extractionMeta == null) {
                try { extractionMeta = objectMapper.readValue(m.getExtractionMetadata(), Map.class); }
                catch (Exception ignored) {}
            }
        }

        // AI 生成结构化内容
        String grainText = grains.stream()
                .map(g -> String.format("【%s】%s | 思考:%s | 话术:%s",
                        g.getSceneTag(), trunc(g.getSceneDescription(), 60),
                        trunc(g.getExpertThought(), 60), trunc(g.getStandardScript(), 60)))
                .collect(Collectors.joining("\n"));
        String domain = domainConfigLoader.resolveDomain(skill);
        ReportContent content = generateReportContent(
                rawText.substring(0, Math.min(4000, rawText.length())), grainText, extractionMeta, domain);

        // 构建 FreeMarker 模型
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("ownerName", getOwnerName(skill));
        model.put("date", LocalDate.now().toString());
        model.put("grainCount", grains.size());
        model.put("empty", false);

        // 统计字段（供报告模板封面 KPI 使用）
        model.put("sceneCount", grains.stream().map(ExperienceGrain::getSceneTag).filter(t -> t != null && !t.isBlank()).distinct().count());
        model.put("avgQualityScore", grains.stream().map(ExperienceGrain::getQualityScore).filter(s -> s != null).mapToDouble(s -> s).average().orElse(0));
        model.put("domainName", domain != null ? domain : "销售");

        // 模式
        @SuppressWarnings("unchecked")
        Map<String, Object> patterns = extractionMeta != null
                ? (Map<String, Object>) extractionMeta.get("patterns") : null;
        if (patterns != null) {
            model.put("oneliner", patterns.getOrDefault("oneliner", ""));
            model.put("methodologyName", patterns.getOrDefault("methodologyName", ""));
            model.put("coreHabits", patterns.getOrDefault("coreHabits", List.of()));
            model.put("differentiators", patterns.getOrDefault("differentiators", List.of()));
        } else {
            model.put("oneliner", "");
        }

        // AI 生成的结构（DTO → Map，FreeMarker 需要 Map）
        if (content.caseSummary != null) {

            model.put("caseSummary", objectMapper.convertValue(content.caseSummary, Map.class));

        }
        if (content.eventProcess != null) {

            model.put("eventProcess", asMapList(content.eventProcess));

        }
        if (content.customerTraits != null) {

            model.put("customerTraits", content.customerTraits);

        }
        if (content.demandLayers != null) {

            model.put("demandLayers", objectMapper.convertValue(content.demandLayers, Map.class));

        }
        if (content.decisionChain != null) {

            model.put("decisionChain", content.decisionChain);

        }
        if (content.keyPersons != null) {

            model.put("keyPersons", asMapList(content.keyPersons));

        }
        if (content.strategies != null) {

            model.put("strategies", asMapList(content.strategies));

        }
        if (content.tactics != null) {

            model.put("tactics", asMapList(content.tactics));

        }
        if (content.faq != null) {

            model.put("faq", asMapList(content.faq));

        }
        if (content.donts != null) {

            model.put("donts", content.donts);

        }

        // 颗粒（用于附录表格）
        model.put("grains", grains);

        // 元数据中的 FAQ/narrative 也可注入
        if (extractionMeta != null) {
            Object pipelineFaq = extractionMeta.get("faq");
            if (pipelineFaq != null && (content.faq == null || content.faq.isEmpty())) {
                model.put("faq", pipelineFaq);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> narrative = (Map<String, Object>) extractionMeta.get("narrative");
            if (narrative != null && content.eventProcess == null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> storyline = (Map<String, Object>) narrative.get("storyline");
                if (storyline != null) {
                    model.put("eventProcess", storyline.get("phases"));
                }
            }
        }

        return model;
    }

    private ReportContent generateReportContent(String sourceText, String grainText, Map<String, Object> meta, String domain) {
        ReportContent result = new ReportContent();

        // Part1
        try {
            String json = chatClient.prompt().user(
                promptLoader.format("extraction_report_part1.md", Map.of("source_text", sourceText), domain))
                .call().content();
            if (json != null) {
                String clean = cleanJson(json);
                Map<String, Object> p = objectMapper.readValue(clean, Map.class);
                result.caseSummary = objectMapper.convertValue(p.get("caseSummary"), CaseSummary.class);
                result.eventProcess = convertList(p.get("eventProcess"), EventStage.class);
                result.customerTraits = convertStringList(p.get("customerTraits"));
                result.demandLayers = objectMapper.convertValue(p.get("demandLayers"), DemandLayers.class);
                result.decisionChain = convertStringList(p.get("decisionChain"));
                result.keyPersons = convertList(p.get("keyPersons"), KeyPerson.class);
            }
        } catch (Exception e) { log.warn("Part1失败: {}", e.getMessage()); }

        // Part2
        try {
            String json = chatClient.prompt().user(
                promptLoader.format("extraction_report_part2.md", Map.of("grain_text", grainText), domain))
                .call().content();
            if (json != null) {
                String clean = cleanJson(json);
                Map<String, Object> p = objectMapper.readValue(clean, Map.class);
                result.strategies = convertList(p.get("strategies"), Strategy.class);
                result.tactics = convertList(p.get("tactics"), Tactic.class);
                result.faq = convertList(p.get("faq"), FaqItem.class);
                result.donts = convertStringList(p.get("donts"));
            }
        } catch (Exception e) { log.warn("Part2失败: {}", e.getMessage()); }

        return result;
    }

    private String renderEmptyPage(String ownerName, String statusMsg) {
        return String.format("""
            <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><title>报告 · %s</title>
            <style>body{font-family:'PingFang SC',sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;background:#F7F8FA;margin:0}.card{background:#fff;border-radius:12px;padding:48px;text-align:center;max-width:480px;box-shadow:0 2px 12px rgba(0,0,0,0.06)}h1{font-size:24px;color:#1A2B4C;margin-bottom:16px}p{color:#6B7280;font-size:15px}.status{display:inline-block;margin-top:16px;padding:8px 20px;border-radius:20px;font-size:14px;background:#FFF3E0;color:#E65100}</style></head><body>
            <div class="card"><h1>📋 报告生成中</h1><p>%s</p><div class="status">⏳ 请稍后刷新</div></div></body></html>""", ownerName, statusMsg);
    }

    private String cleanJson(String raw) {
        String s = raw.trim();
        { if (s.startsWith("```")) s = s.replaceAll("```json\\s*|```\\s*", "").trim(); }
        return s;
    }

    private String buildStatusMsg(List<SkillMaterial> materials) {
        { if (materials.isEmpty()) return "暂无素材，请先上传。"; }
        { if (materials.stream().anyMatch(m -> "uploaded".equals(m.getStatus()))) return "素材等待解析中，系统每30秒自动扫描。"; }
        return "素材清洗中，完成后自动生成报告。";
    }

    private String getOwnerName(Skill skill) {
        String n = skill.getDisplayName();
        { if (n == null || n.isBlank()) n = skill.getOwnerName(); }
        return n != null && !n.isBlank() ? n : "未命名";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(List<?> dtos) {
        return (List<Map<String, Object>>) (List<?>) dtos.stream()
                .map(d -> objectMapper.convertValue(d, Map.class))
                .collect(Collectors.toList());
    }

    private String trunc(String s, int max) {
        { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, max) + "..."; }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> convertList(Object obj, Class<T> clz) {
         if (obj instanceof List) return objectMapper.convertValue(obj,
                objectMapper.getTypeFactory().constructCollectionType(List.class, clz));
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> convertStringList(Object obj) {
        if (obj instanceof List) return objectMapper.convertValue(obj,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        return List.of();
    }

    private void saveReportHistory(UUID skillId, int grainCount) {
        try {
            String version = "v1-" + LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            ReportHistory history = ReportHistory.builder()
                .id(UUID.randomUUID())
                .skillId(skillId)
                .version(version)
                .generatedAt(LocalDateTime.now())
                .grainCount(grainCount)
                .materialIds("[]")
                .build();
            historyRepository.save(history);
            log.info("正式报告版本已记录, skillId: {}, version: {}, grains: {}", skillId, version, grainCount);
        } catch (Exception e) {
            log.warn("报告版本记录失败: {}", e.getMessage());
        }
    }

    // ---- DTOs ----
    public static class CaseSummary { public String dealTarget, customerIndustry, customerProfile, followCycle, businessValue; }
    public static class EventStage { public int stage; public String title, content; }
    public static class DemandLayers { public String surface, real, hidden; }
    public static class KeyPerson { public String role, influence; }
    public static class Strategy { public String name, principle; }
    public static class Tactic { public String name, method; }
    public static class FaqItem { public String question, answer; }
    public static class ReportContent {
        public CaseSummary caseSummary;
        public List<EventStage> eventProcess;
        public List<String> customerTraits;
        public DemandLayers demandLayers;
        public List<String> decisionChain;
        public List<KeyPerson> keyPersons;
        public List<Strategy> strategies;
        public List<Tactic> tactics;
        public List<FaqItem> faq;
        public List<String> donts;
    }
}
