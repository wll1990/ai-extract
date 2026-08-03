package com.aiextract.service;

import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.ExtractionDropLog;
import com.aiextract.model.SkillMaterial;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.ExtractionDropLogRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 素材萃取审计服务 — 为指定素材生成逐 chunk 的萃取质量报告。
 *
 * <p>数据来源：
 * <ul>
 *   <li>{@link SkillMaterial#getExtractionMetadata} — 管线中记录的 chunkResults + verifyDetails</li>
 *   <li>{@link ExperienceGrainRepository#findBySourceMaterialId} — 最终落库的颗粒</li>
 *   <li>{@link ExtractionDropLogRepository#findByMaterialIdOrderByCreatedAtAsc} — 淘汰记录</li>
 * </ul>
 *
 * @author AI Extract Team
 * @since 2026-08-03
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialAuditService {

    private final SkillMaterialRepository materialRepository;
    private final ExperienceGrainRepository grainRepository;
    private final ExtractionDropLogRepository dropLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * 生成素材萃取审计报告。
     *
     * @param materialId 素材 ID
     * @return 结构化审计报告，供 admin API 和 grain-audit.py 使用
     */
    public Map<String, Object> buildAuditReport(UUID materialId) {
        SkillMaterial material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("素材不存在: " + materialId));

        Map<String, Object> report = new LinkedHashMap<>();

        // ── 1. 素材基本信息 ──
        report.put("materialInfo", buildMaterialInfo(material));

        // ── 2. 管线追踪信息 ──
        report.put("pipelineTrace", buildPipelineTrace(material));

        // ── 3. chunk 级提取详情 ──
        report.put("chunkDetail", extractChunkDetails(material));

        // ── 4. 验证详情 ──
        report.put("verifyDetail", extractVerifyDetails(material));

        // ── 5. 淘汰记录 ──
        List<ExtractionDropLog> dropLogs = dropLogRepository.findByMaterialIdOrderByCreatedAtAsc(materialId);
        report.put("dropLogs", dropLogs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", d.getStage());
            m.put("chunkIndex", d.getChunkIndex());
            m.put("contentPreview", d.getContentPreview());
            m.put("collidedGrainId", d.getCollidedGrainId() != null ? d.getCollidedGrainId().toString() : null);
            m.put("similarity", d.getSimilarity());
            m.put("detail", d.getDetail());
            return m;
        }).toList());

        // ── 6. 最终颗粒 ──
        List<ExperienceGrain> finalGrains = grainRepository.findBySourceMaterialId(materialId);
        report.put("finalGrains", finalGrains.stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId().toString());
            m.put("sceneTag", g.getSceneTag());
            m.put("sceneDescription", g.getSceneDescription());
            m.put("expertThought", g.getExpertThought());
            m.put("standardScript", g.getStandardScript());
            m.put("qualityScore", g.getQualityScore());
            m.put("difficultyLevel", g.getDifficultyLevel());
            m.put("weight", g.getWeight());
            m.put("status", g.getStatus());
            return m;
        }).toList());

        // ── 7. 汇总 ──
        report.put("summary", buildSummary(report));

        return report;
    }

    private Map<String, Object> buildMaterialInfo(SkillMaterial m) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", m.getId().toString());
        info.put("fileName", m.getFileName());
        info.put("status", m.getStatus());
        info.put("materialType", m.getMaterialType());
        info.put("fileType", m.getFileType());
        info.put("fileSize", m.getFileSize());
        info.put("textLength", m.getParsedContent() != null ? m.getParsedContent().length() : 0);
        info.put("retryCount", m.getRetryCount());
        info.put("analysisNotes", m.getAnalysisNotes());
        return info;
    }

    private Map<String, Object> buildPipelineTrace(SkillMaterial m) {
        Map<String, Object> trace = new LinkedHashMap<>();
        String meta = m.getExtractionMetadata();
        if (meta != null && !meta.isBlank()) {
            try {
                Map<String, Object> metaMap = objectMapper.readValue(meta,
                        new TypeReference<Map<String, Object>>() {});
                // 情境信息
                @SuppressWarnings("unchecked")
                Map<String, Object> ctx = (Map<String, Object>) metaMap.get("context");
                if (ctx != null) {
                    trace.put("context", ctx);
                }
                // 计数
                trace.put("verifiedCount", metaMap.getOrDefault("verifiedCount", 0));
                trace.put("rejectedCount", metaMap.getOrDefault("rejectedCount", 0));
                // 模式
                if (metaMap.containsKey("patterns")) {
                    trace.put("patterns", metaMap.get("patterns"));
                }
            } catch (Exception e) {
                log.debug("解析 extractionMetadata 部分字段失败: {}", e.getMessage());
                trace.put("parseError", e.getMessage());
            }
        }
        trace.put("cleaningNotes", m.getAnalysisNotes());
        return trace;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractChunkDetails(SkillMaterial m) {
        String meta = m.getExtractionMetadata();
        if (meta == null || meta.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> metaMap = objectMapper.readValue(meta,
                    new TypeReference<Map<String, Object>>() {});
            Object chunkResults = metaMap.get("chunkResults");
            if (chunkResults instanceof List) {
                return (List<Map<String, Object>>) chunkResults;
            }
        } catch (Exception e) {
            log.debug("解析 chunkResults 失败: {}", e.getMessage());
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractVerifyDetails(SkillMaterial m) {
        String meta = m.getExtractionMetadata();
        if (meta == null || meta.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> metaMap = objectMapper.readValue(meta,
                    new TypeReference<Map<String, Object>>() {});
            Object verifyDetails = metaMap.get("verifyDetails");
            if (verifyDetails instanceof List) {
                return (List<Map<String, Object>>) verifyDetails;
            }
        } catch (Exception e) {
            log.debug("解析 verifyDetails 失败: {}", e.getMessage());
        }
        return List.of();
    }

    private Map<String, Object> buildSummary(Map<String, Object> report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunkDetail = (List<Map<String, Object>>) report.get("chunkDetail");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> verifyDetail = (List<Map<String, Object>>) report.get("verifyDetail");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> finalGrains = (List<Map<String, Object>>) report.get("finalGrains");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dropLogs = (List<Map<String, Object>>) report.get("dropLogs");

        summary.put("totalChunks", chunkDetail.size());
        summary.put("nonEmptyChunks", chunkDetail.stream()
                .filter(c -> ((Number) c.getOrDefault("extractedCount", 0)).intValue() > 0).count());
        summary.put("totalCandidates", verifyDetail.stream()
                .filter(v -> "APPROVE".equals(v.get("verdict"))).count()
                + verifyDetail.stream().filter(v -> "REJECT".equals(v.get("verdict"))).count());
        summary.put("verifiedGrains", verifyDetail.stream()
                .filter(v -> "APPROVE".equals(v.get("verdict"))).count());
        summary.put("rejectedGrains", verifyDetail.stream()
                .filter(v -> "REJECT".equals(v.get("verdict"))).count());
        summary.put("finalGrains", finalGrains.size());
        summary.put("droppedEntries", dropLogs.size());
        return summary;
    }
}
