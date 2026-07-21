package com.aiextract.controller;

import com.aiextract.common.ErrorMessages;
import com.aiextract.exception.BusinessException;
import com.aiextract.common.ApiResponse;
import org.springframework.http.HttpStatus;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillMaterial;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.service.MaterialCleaningService;
import com.aiextract.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Slf4j
@RestController
/**
 * @author AI Extract Team
 */
@RequiredArgsConstructor
public class SkillMaterialController {
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_FAQ = "faq";
    private static final String KEY_NARRATIVE = "narrative";
    private static final String KEY_PATTERNS = "patterns";
    private static final String KEY_REJECTED_COUNT = "rejectedCount";
    private static final String KEY_SKILL_ID = "skillId";
    private static final String KEY_TEXT = "text";
    private static final String KEY_VERIFIED_COUNT = "verifiedCount";


    private final SkillMaterialRepository materialRepository;
    private final SkillRepository skillRepository;
    private final ExperienceGrainRepository grainRepository;
    private final MaterialCleaningService cleaningService;
    private final JwtUtil jwtUtil;
    private final com.aiextract.repository.UserRepository userRepository;
    private final com.aiextract.repository.SpaceRepository spaceRepository;
    private final ObjectMapper objectMapper;

    private String getToken() {
        return (String) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getCredentials();
    }

    /** 上传素材 — Controller 只做参数校验+路由，业务逻辑在 Service */
    @PostMapping("/admin/materials/upload")
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "skillId", required = false) UUID skillId,
            @RequestParam(value = "skillName", required = false) String skillName,
            @RequestParam(value = "domain", required = false) String domain) {

        if (isNoTargetSpecified(spaceId, skillId, skillName)) {
            throw new BusinessException(400, "请选择空间或分身");
        }

        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        List<Map<String, Object>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) { continue; }
            results.add(cleaningService.uploadMaterialToSpace(file, spaceId, skillId, skillName, userId, domain));
            if (skillId == null && !results.isEmpty()) {
                skillId = UUID.fromString((String) results.get(0).get(KEY_SKILL_ID));
            }
        }
        return ApiResponse.success(Map.of("uploaded", results.size(), "results", results));
    }

    /** 文本素材上传 — 直接粘贴文本，不走文件存盘 */
    @PostMapping("/admin/materials/text")
    public ApiResponse<Map<String, Object>> uploadText(@RequestBody Map<String, Object> body) {
        String text = (String) body.get("text");
        if (text == null || text.isBlank()) {
            throw new BusinessException(400, "文本内容不能为空");
        }
        if (text.length() < 10) {
            throw new BusinessException(400, "文本内容至少10个字");
        }

        UUID spaceId = body.containsKey("spaceId") ? UUID.fromString((String) body.get("spaceId")) : null;
        String skillIdStr = (String) body.get("skillId");
        UUID skillId = (skillIdStr != null && !skillIdStr.isEmpty()) ? UUID.fromString(skillIdStr) : null;
        String skillName = (String) body.getOrDefault("skillName", null);
        String domain = (String) body.getOrDefault("domain", null);
        String title = (String) body.getOrDefault("title", null);

        if (isNoTargetSpecified(spaceId, skillId, skillName)) {
            throw new BusinessException(400, "请选择空间或分身");
        }

        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        Map<String, Object> result = cleaningService.uploadTextMaterial(text, spaceId, skillId, skillName, userId, domain, title);
        return ApiResponse.success(Map.of("uploaded", 1, "results", List.of(result)));
    }

    /** 分身列表（供上传时下拉选择，仅当前企业） */
    @GetMapping("/admin/skills/picker")
    public ApiResponse<List<Map<String, Object>>> picker() {
        UUID companyId = jwtUtil.getCompanyIdFromToken(getToken());
        // 查该企业所有空间
        List<UUID> companyUserIds = userRepository.findByCompanyId(companyId).stream()
                .map(com.aiextract.model.User::getId).toList();
        List<UUID> companySpaceIds = spaceRepository.findByUserIdIn(companyUserIds).stream()
                .map(com.aiextract.model.Space::getId).toList();
        List<Map<String, Object>> list = new ArrayList<>();
        List<Skill> skills;
        if (companySpaceIds.isEmpty()) {
            skills = List.of();
        } else {
            skills = skillRepository.findBySpaceIdIn(companySpaceIds, PageRequest.of(0, 200)).getContent();
        }
        for (Skill s : skills) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId().toString());
            String name = s.getDisplayName() != null ? s.getDisplayName()
                : s.getOwnerName() != null ? s.getOwnerName()
                : "未命名";
            item.put("name", name);
            item.put("status", s.getStatus());
            list.add(item);
        }
        return ApiResponse.success(list);
    }

    /** 分身素材列表（分页） */
    @GetMapping("/admin/skills/{skillId}/materials")
    public ApiResponse<Page<SkillMaterial>> listMaterials(
            @PathVariable UUID skillId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
            materialRepository.findBySkillIdOrderByCreatedAtDesc(skillId, PageRequest.of(page - 1, size)));
    }

    /** 素材详情 + 该素材产生的颗粒 */
    @GetMapping("/admin/skills/{skillId}/materials/{materialId}/detail")
    public ApiResponse<Map<String, Object>> getMaterialDetail(
            @PathVariable UUID skillId, @PathVariable UUID materialId) {
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        if (!m.getSkillId().equals(skillId)) { throw new BusinessException(HttpStatus.FORBIDDEN.value(), ErrorMessages.MATERIAL_NOT_BELONG_TO_SKILL); }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", m.getId().toString());
        data.put("fileName", m.getFileName());
        data.put("fileType", m.getFileType());
        data.put("status", m.getStatus());
        data.put("version", m.getVersion());
        data.put("analysisNotes", m.getAnalysisNotes());
        data.put("createdAt", m.getCreatedAt().toString());

        try {
            if (m.getExtractionMetadata() != null) {
                Map<String, Object> meta = objectMapper.readValue(m.getExtractionMetadata(), Map.class);
                data.put("reportVersion", meta.getOrDefault("reportVersion", ""));
                if (meta.containsKey(KEY_CONTEXT)) { data.put(KEY_CONTEXT, meta.get(KEY_CONTEXT)); }
                if (meta.containsKey(KEY_PATTERNS)) { data.put(KEY_PATTERNS, meta.get(KEY_PATTERNS)); }
                if (meta.containsKey(KEY_FAQ)) { data.put(KEY_FAQ, meta.get(KEY_FAQ)); }
                if (meta.containsKey(KEY_NARRATIVE)) { data.put(KEY_NARRATIVE, meta.get(KEY_NARRATIVE)); }
                if (meta.containsKey(KEY_VERIFIED_COUNT)) { data.put(KEY_VERIFIED_COUNT, meta.get(KEY_VERIFIED_COUNT)); }
                if (meta.containsKey(KEY_REJECTED_COUNT)) { data.put(KEY_REJECTED_COUNT, meta.get(KEY_REJECTED_COUNT)); }
            }
        } catch (Exception ignored) {}

        List<ExperienceGrain> grains = grainRepository.findBySourceMaterialId(materialId);
        List<Map<String, Object>> grainList = new ArrayList<>();
        for (ExperienceGrain g : grains) {
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
            grainList.add(gm);
        }
        data.put("grains", grainList);

        return ApiResponse.success(data);
    }

    @PutMapping("/admin/skills/{skillId}/materials/{materialId}")
    public ApiResponse<Void> updateMaterialStatus(
            @PathVariable UUID skillId, @PathVariable UUID materialId,
            @RequestBody Map<String, String> body) {
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        if (!m.getSkillId().equals(skillId)) { throw new BusinessException(HttpStatus.FORBIDDEN.value(), ErrorMessages.MATERIAL_NOT_BELONG_TO_SKILL); }
        m.setStatus(body.getOrDefault("status", m.getStatus()));
        materialRepository.save(m);
        return ApiResponse.success();
    }

    @DeleteMapping("/admin/skills/{skillId}/materials/{materialId}")
    public ApiResponse<Void> deleteMaterial(
            @PathVariable UUID skillId, @PathVariable UUID materialId) {
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        if (!m.getSkillId().equals(skillId)) { throw new BusinessException(HttpStatus.FORBIDDEN.value(), ErrorMessages.MATERIAL_NOT_BELONG_TO_SKILL); }
        materialRepository.delete(m);
        return ApiResponse.success();
    }

    /** 手动补录文字 — 针对图片/音频等无法自动解析的素材 */
    @PutMapping("/admin/materials/{materialId}/manual-text")
    public ApiResponse<Void> submitManualText(
            @PathVariable UUID materialId,
            @RequestBody Map<String, String> body) {
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        String text = body.get(KEY_TEXT);
        if (text == null || text.isBlank()) {
            throw new BusinessException(400, "文字内容不能为空");
        }
        m.setParsedContent(text.trim());
        m.setAnalysisNotes("人工补录文字，长度: " + text.length() + "字");
        materialRepository.save(m);
        log.info("手动补录文字完成, materialId: {}, 长度: {}字", materialId, text.length());
        return ApiResponse.success();
    }

    /** 管理员手动重试 — 重置 retry_count，素材回到 uploaded 重新进入管线 */
    @PutMapping("/admin/materials/{materialId}/retry")
    public ApiResponse<Void> retryMaterial(@PathVariable UUID materialId) {
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        m.setRetryCount(0);
        m.setStatus("uploaded");
        m.setAnalysisNotes("管理员手动重试");
        materialRepository.save(m);
        log.info("管理员手动重试素材, materialId: {}", materialId);
        return ApiResponse.success();
    }

    private boolean isNoTargetSpecified(UUID spaceId, UUID skillId, String skillName) {
        return spaceId == null && skillId == null && (skillName == null || skillName.isBlank());
    }
}
