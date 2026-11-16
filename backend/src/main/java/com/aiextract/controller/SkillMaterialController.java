package com.aiextract.controller;

import com.aiextract.common.ErrorMessages;
import com.aiextract.common.UploadLimits;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.common.ApiResponse;
import org.springframework.http.HttpStatus;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillMaterial;
import com.aiextract.model.Space;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.service.MaterialAuditService;
import com.aiextract.service.MaterialCleaningService;
import com.aiextract.service.MaterialCleaningScheduler;
import com.aiextract.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
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
    private final MaterialCleaningScheduler cleaningScheduler;
    private final MaterialAuditService auditService;
    private final JwtUtil jwtUtil;
    private final com.aiextract.repository.UserRepository userRepository;
    private final com.aiextract.repository.SpaceRepository spaceRepository;
    private final CompanyScopeService companyScopeService;
    private final ObjectMapper objectMapper;
    private final com.aiextract.service.FileStorageService fileStorageService;

    @org.springframework.beans.factory.annotation.Value("${app.material.upload.max-text-length:100000}")
    private int maxTextLength;

    @org.springframework.beans.factory.annotation.Value("${app.material.upload.supported-formats:txt,md,csv,html,htm,pdf,docx,pptx,png,jpg,jpeg,mp3,wav}")
    private String supportedFormats;

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

        // 上传目标必须属于当前用户/当前企业
        if (skillId != null) {
            assertSkillAccess(skillId);
        } else if (spaceId != null) {
            assertSpaceAccess(spaceId);
        }

        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        // 先整体校验，避免上传到一半才发现格式/大小不合法，留下半批数据
        for (MultipartFile file : files) {
            if (file.isEmpty()) { continue; }
            if (file.getSize() > UploadLimits.MAX_FILE_SIZE) {
                throw new BusinessException(413,
                    "\"" + file.getOriginalFilename() + "\" 超过 " + UploadLimits.MAX_FILE_SIZE_MB
                        + "MB，请压缩或拆分后上传");
            }
            String lowerName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";
            if (!lowerName.isEmpty() && !isSupportedFormat(lowerName)) {
                throw new BusinessException(400, "暂不支持 \"" + getFileExt(lowerName)
                    + "\" 格式。支持格式：" + String.join(" / ", supportedFormats.split(",")));
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("fileName", file.getOriginalFilename());
                empty.put("acceptance", Map.of("passed", false, "rejectReason", "空文件，请选择有内容的文件"));
                results.add(empty);
                continue;
            }
            Map<String, Object> item = cleaningService.uploadMaterialToSpace(
                    file, spaceId, skillId, skillName, userId, domain);
            results.add(item);
            if (skillId == null && item.get(KEY_SKILL_ID) != null) {
                skillId = UUID.fromString((String) item.get(KEY_SKILL_ID));
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
        if (text.length() > maxTextLength) {
            throw new BusinessException(413, "文本内容 " + text.length()
                + " 字，超过 " + maxTextLength + " 字上限。建议拆分为多个片段分批上传，处理速度更快、萃取质量更高");
        }

        // JSON 里 spaceId/skillId 可能是显式 null 或空串，不能直接 UUID.fromString
        UUID spaceId = parseUuid(body.get("spaceId"));
        UUID skillId = parseUuid(body.get("skillId"));
        String skillName = (String) body.getOrDefault("skillName", null);
        String domain = (String) body.getOrDefault("domain", null);
        String title = (String) body.getOrDefault("title", null);

        if (isNoTargetSpecified(spaceId, skillId, skillName)) {
            throw new BusinessException(400, "请选择空间或分身");
        }

        // 上传目标必须属于当前用户/当前企业
        if (skillId != null) {
            assertSkillAccess(skillId);
        } else if (spaceId != null) {
            assertSpaceAccess(spaceId);
        }

        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        Map<String, Object> result = cleaningService.uploadTextMaterial(text, spaceId, skillId, skillName, userId, domain, title);
        return ApiResponse.success(Map.of("uploaded", 1, "results", List.of(result)));
    }

    /** 分身列表（供上传时下拉选择，仅当前企业；员工只看自己的） */
    @GetMapping("/admin/skills/picker")
    public ApiResponse<List<Map<String, Object>>> picker() {
        UUID currentUserId = jwtUtil.getUserIdFromToken(getToken());
        String role = jwtUtil.getRoleFromToken(getToken());

        List<UUID> targetSpaceIds;
        if (RolePermissions.EMPLOYEE.equals(role)) {
            // 员工只看自己空间下的分身
            targetSpaceIds = spaceRepository.findByUserId(currentUserId).stream()
                    .map(com.aiextract.model.Space::getId).toList();
        } else {
            // 管理员看本企业；super_admin（companyId=null）看全量
            UUID companyId = TokenContext.getCompanyId();
            if (companyId == null) {
                // super_admin — 查全量 skill，不做 space 过滤
                List<Map<String, Object>> allList = new ArrayList<>();
                for (Skill s : skillRepository.findAll(PageRequest.of(0, 200)).getContent()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", s.getId().toString());
                    item.put("name", s.getDisplayName() != null ? s.getDisplayName()
                        : s.getOwnerName() != null ? s.getOwnerName() : "未命名");
                    item.put("status", s.getStatus());
                    allList.add(item);
                }
                // 过滤掉仅有 interview 素材的 skill
                filterRealMaterialSkills(allList);
                return ApiResponse.success(allList);
            }
            List<UUID> companyUserIds = userRepository.findByCompanyId(companyId).stream()
                    .map(com.aiextract.model.User::getId).toList();
            targetSpaceIds = spaceRepository.findByUserIdIn(companyUserIds).stream()
                    .map(com.aiextract.model.Space::getId).toList();
        }

        List<Map<String, Object>> list = new ArrayList<>();
        List<Skill> skills;
        if (targetSpaceIds.isEmpty()) {
            skills = List.of();
        } else {
            skills = skillRepository.findBySpaceIdIn(targetSpaceIds, PageRequest.of(0, 200)).getContent();
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
        filterRealMaterialSkills(list);
        return ApiResponse.success(list);
    }

    /** 过滤掉仅有 interview 素材（无用户手动上传）的 skill */
    private void filterRealMaterialSkills(List<Map<String, Object>> list) {
        if (list.isEmpty()) return;
        List<UUID> skillIds = list.stream()
            .map(m -> UUID.fromString((String) m.get("id"))).toList();
        Set<UUID> rich = new HashSet<>(materialRepository.findSkillIdsWithRealMaterials(skillIds));
        list.removeIf(m -> !rich.contains(UUID.fromString((String) m.get("id"))));
    }

    /** 分身素材列表（分页） */
    @GetMapping("/admin/skills/{skillId}/materials")
    public ApiResponse<Page<Map<String, Object>>> listMaterials(
            @PathVariable UUID skillId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        assertSkillAccess(skillId);
        // 列表只返回安全字段：不暴露 fileUrl（存储路径）与 parsedContent（全文）
        return ApiResponse.success(
            materialRepository.findBySkillIdExcludingType(skillId, "interview",
                    PageRequest.of(page - 1, size))
                .map(this::toMaterialListItem));
    }

    /** 素材列表投影 — 详情/全文请走 /materials/{id}/detail */
    private Map<String, Object> toMaterialListItem(SkillMaterial m) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", m.getId().toString());
        item.put("skillId", m.getSkillId() != null ? m.getSkillId().toString() : null);
        item.put("fileName", m.getFileName());
        item.put("fileType", m.getFileType());
        item.put("fileSize", m.getFileSize());
        item.put("status", m.getStatus());
        item.put("retryCount", m.getRetryCount());
        item.put("analysisNotes", m.getAnalysisNotes());
        item.put("parsedChars", m.getParsedContent() != null ? m.getParsedContent().length() : 0);
        item.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
        return item;
    }

    /** 素材详情 + 该素材产生的颗粒 */
    @GetMapping("/admin/skills/{skillId}/materials/{materialId}/detail")
    public ApiResponse<Map<String, Object>> getMaterialDetail(
            @PathVariable UUID skillId, @PathVariable UUID materialId) {
        assertSkillAccess(skillId);
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
        assertSkillAccess(skillId);
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        if (!m.getSkillId().equals(skillId)) { throw new BusinessException(HttpStatus.FORBIDDEN.value(), ErrorMessages.MATERIAL_NOT_BELONG_TO_SKILL); }
        String status = body.get("status");
        // 只允许「放弃」与「恢复重新排队」，不允许伪造 extracted/parsed 等流水线终态
        if ("discarded".equals(status)) {
            m.setStatus(status);
            materialRepository.save(m);
            return ApiResponse.success();
        }
        if ("uploaded".equals(status)) {
            if (!"discarded".equals(m.getStatus())) {
                throw new BusinessException(409, "仅已放弃的素材可以恢复");
            }
            // 已产出颗粒的素材不能回到 uploaded：调度器会重新解析+萃取，生成第二套颗粒
            int grains = grainRepository.findBySourceMaterialId(materialId).size();
            if (grains > 0) {
                throw new BusinessException(409, "这份素材已经萃取过 " + grains
                    + " 条经验颗粒，不能恢复到待处理；请删除已产出的颗粒或重新上传素材");
            }
            cleaningScheduler.resetRetryCount(materialId);
            log.info("已恢复放弃的素材 materialId={}", materialId);
            return ApiResponse.success();
        }
        throw new BusinessException(400, "仅支持 discarded（放弃）或 uploaded（恢复）");
    }

    @DeleteMapping("/admin/skills/{skillId}/materials/{materialId}")
    public ApiResponse<Void> deleteMaterial(
            @PathVariable UUID skillId, @PathVariable UUID materialId) {
        assertSkillAccess(skillId);
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        if (!m.getSkillId().equals(skillId)) { throw new BusinessException(HttpStatus.FORBIDDEN.value(), ErrorMessages.MATERIAL_NOT_BELONG_TO_SKILL); }
        fileStorageService.delete(m.getFileUrl());
        materialRepository.delete(m);
        return ApiResponse.success();
    }

    /** 手动补录文字 — 针对图片/音频等无法自动解析的素材 */
    @PutMapping("/admin/materials/{materialId}/manual-text")
    public ApiResponse<Void> submitManualText(
            @PathVariable UUID materialId,
            @RequestBody Map<String, String> body) {
        assertMaterialAccess(materialId);
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        String text = body.get(KEY_TEXT);
        if (text == null || text.isBlank()) {
            throw new BusinessException(400, "文字内容不能为空");
        }
        // 只允许尚未成功萃取的素材补录，避免已萃取素材被重新灌入管线重复产颗粒
        if (!java.util.Set.of("uploaded", "parse_failed", "cleaning_failed", "rejected")
                .contains(m.getStatus())) {
            throw new BusinessException(409, "该素材已处理完成，不能补录文字");
        }
        String trimmed = text.trim();
        if (trimmed.length() > maxTextLength) {
            throw new BusinessException(413, "文本过长(" + trimmed.length() + "字)，超过上限("
                + maxTextLength + "字)，请拆分后分批上传");
        }
        // 人工补录同样脱敏后再入库（手机号/身份证/银行卡/座机/邮箱）
        m.setParsedContent(com.aiextract.service.PiiMasker.mask(trimmed));
        m.setAnalysisNotes("人工补录文字，长度: " + trimmed.length() + "字");
        // 补录后必须进入清洗队列，否则正文永远停在 uploaded
        m.setStatus("parsed");
        m.setRetryCount(0);
        materialRepository.save(m);
        log.info("手动补录文字完成, materialId: {}, 长度: {}字", materialId, trimmed.length());
        return ApiResponse.success();
    }

    /** 管理员手动重试 — 重置 retry_count，素材回到 uploaded 重新进入管线 */
    @PutMapping("/admin/materials/{materialId}/retry")
    public ApiResponse<Void> retryMaterial(@PathVariable UUID materialId) {
        assertMaterialAccess(materialId);
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        if (!java.util.Set.of("uploaded", "parse_failed", "cleaning_failed", "rejected")
                .contains(m.getStatus())) {
            throw new BusinessException(400, "当前状态不能重试：" + m.getStatus());
        }
        // 统一走调度器的短事务，保证 retry_count、状态、备注一次写对
        cleaningScheduler.resetRetryCount(materialId);
        log.info("管理员手动重试素材, materialId: {}", materialId);
        return ApiResponse.success();
    }

    /**
     * 分身卡在 generating 时的整体重试：把失败/未处理的素材重新放回管线。
     *
     * <p>只重置「未成功」的状态，已 extracted / analyzed 的素材不会重复萃取。</p>
     */
    @PostMapping("/admin/skills/{skillId}/retry-pipeline")
    public ApiResponse<Map<String, Object>> retryPipeline(@PathVariable UUID skillId) {
        companyScopeService.assertSkillOwnership(skillId);
        // 只查 ID：避免把每条素材的 parsed_content TEXT 全量读入内存
        List<UUID> retryableIds = materialRepository.findIdsBySkillIdAndStatusIn(skillId, java.util.Set.of(
                "uploaded", "parse_failed", "cleaning_failed", "rejected", "failed"));
        if (retryableIds.isEmpty()) {
            throw new BusinessException(400, "没有可重试的素材（均已处理完成），请补充新素材");
        }
        int reset = 0;
        for (UUID id : retryableIds) {
            cleaningScheduler.resetRetryCount(id);
            reset++;
        }
        log.info("分身整体重试 skillId={} reset={} total={}", skillId, reset, retryableIds.size());
        return ApiResponse.success(Map.of("reset", reset, "total", retryableIds.size()));
    }

    /**
     * 非管理员素材上传 — C端/普通用户给分身传素材。
     * 属主校验：skill → space → space.isOwnedBy(currentUserId)。
     */
    @PostMapping("/skills/{skillId}/materials/upload")
    public ApiResponse<Map<String, Object>> uploadMaterial(
            @PathVariable UUID skillId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "materialType", required = false) String materialType) {
        validateSkillOwnership(skillId);
        UUID userId = jwtUtil.getUserIdFromToken(getToken());

        // 文件格式校验
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(400, "文件名不能为空");
        }
        // 只放行 ai-service 真正能解析的格式；扩展名与大小上限统一走 UploadLimits
        if (!UploadLimits.isMaterialAllowed(originalName)) {
            throw new BusinessException(400,
                "不支持的文件格式，支持 " + UploadLimits.materialExtensionHint());
        }
        if (file.getSize() > UploadLimits.MAX_FILE_SIZE) {
            throw new BusinessException(400, "文件不能超过 " + UploadLimits.MAX_FILE_SIZE_MB + "MB");
        }

        // 保存文件到 OSS：素材与知识/案例统一存储，不落本地磁盘
        String objectKey = fileStorageService.saveMaterialFile(file, "material");

        // 创建素材记录
        SkillMaterial material = SkillMaterial.builder()
            .id(UUID.randomUUID())
            .skillId(skillId)
            .uploadedBy(userId)
            .fileName(originalName)
            .fileUrl(objectKey)
            .fileType(detectFileType(UploadLimits.extensionOf(originalName)))
            .materialType(materialType)
            .fileSize(file.getSize())
            .status("uploaded")
            .retryCount(0)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
        material = materialRepository.save(material);

        log.info("素材上传成功 materialId={} skillId={} fileName={}", material.getId(), skillId, originalName);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("materialId", material.getId().toString());
        result.put("fileName", material.getFileName());
        result.put("status", material.getStatus());
        return ApiResponse.success(result);
    }

    /**
     * C端素材列表（分页）— 含属主校验。
     * 走 /skills/** 权限域（SKILL_USE），C端用户可访问。
     */
    @GetMapping("/skills/{skillId}/materials")
    public ApiResponse<Page<Map<String, Object>>> listMaterialsForOwner(
            @PathVariable UUID skillId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        validateSkillOwnership(skillId);
        return ApiResponse.success(
            materialRepository.findBySkillIdExcludingType(skillId, "interview",
                    PageRequest.of(page - 1, size))
                .map(this::toMaterialListItem));
    }

    /**
     * C端素材详情 — 含属主校验。
     */
    @GetMapping("/skills/{skillId}/materials/{materialId}")
    public ApiResponse<Map<String, Object>> getMaterialDetailForOwner(
            @PathVariable UUID skillId, @PathVariable UUID materialId) {
        validateSkillOwnership(skillId);
        return getMaterialDetail(skillId, materialId);
    }

    /**
     * C端删除素材 — 含属主校验。
     */
    @DeleteMapping("/skills/{skillId}/materials/{materialId}")
    public ApiResponse<Void> deleteMaterialForOwner(
            @PathVariable UUID skillId, @PathVariable UUID materialId) {
        validateSkillOwnership(skillId);
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        if (!m.getSkillId().equals(skillId)) {
            throw new BusinessException(403, "素材不属于该分身");
        }
        materialRepository.delete(m);
        return ApiResponse.success();
    }

    /**
     * 属主校验公共方法 — skill → space → space.isOwnedBy(currentUserId)。
     * 校验通过返回 Skill，失败抛 403/404。
     */
    private Skill validateSkillOwnership(UUID skillId) {
        Skill skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
        validateSpaceOwnership(skill.getSpaceId());
        return skill;
    }

    /** employee/C 端属主校验 — space.isOwnedBy(currentUserId)。 */
    private void validateSpaceOwnership(UUID spaceId) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        Space space = spaceRepository.findById(spaceId)
            .orElseThrow(() -> new BusinessException(404, "空间不存在"));
        if (!space.isOwnedBy(userId)) {
            throw new BusinessException(403, "无权操作");
        }
    }

    /** employee 角色校验 skill 归属，管理员跳过 */
    /** 素材萃取审计报告 — 逐 chunk 提取详情 + 验证分数 + 最终颗粒 */
    @GetMapping("/admin/materials/{materialId}/audit-report")
    public ApiResponse<Map<String, Object>> getAuditReport(@PathVariable UUID materialId) {
        assertMaterialAccess(materialId);
        return ApiResponse.success(auditService.buildAuditReport(materialId));
    }

    /**
     * 素材入口统一归属校验：employee 只能操作自己空间下的分身；
     * company_admin 只能操作本企业分身；super_admin 不受企业范围限制。
     */
    private void assertSkillAccess(UUID skillId) {
        if (RolePermissions.EMPLOYEE.equals(jwtUtil.getRoleFromToken(getToken()))) {
            validateSkillOwnership(skillId);
        } else {
            companyScopeService.assertSkillOwnership(skillId);
        }
    }

    /** 空间入口统一归属校验：employee 只能操作自己的空间，管理员限本企业。 */
    private void assertSpaceAccess(UUID spaceId) {
        if (RolePermissions.EMPLOYEE.equals(jwtUtil.getRoleFromToken(getToken()))) {
            validateSpaceOwnership(spaceId);
        } else {
            companyScopeService.assertSpaceOwnership(spaceId);
        }
    }

    /** 素材归属校验：先通过 material → skill 链路定位，再走统一 skill 校验。 */
    private void assertMaterialAccess(UUID materialId) {
        SkillMaterial m = materialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "素材不存在"));
        assertSkillAccess(m.getSkillId());
    }

    private boolean isSupportedFormat(String lowerName) {
        for (String fmt : supportedFormats.split(",")) {
            if (lowerName.endsWith("." + fmt.trim())) return true;
        }
        return false;
    }

    private String getFileExt(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        return dot > 0 ? lowerName.substring(dot) : "未知";
    }

    private String detectFileType(String lowerName) {
        return com.aiextract.common.FileTypeUtil.detect(lowerName);
    }

    /** 容错解析 UUID：null/空串返回 null，格式错误返回 400 而不是 500 */
    private UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "ID 格式不正确：" + text);
        }
    }

    private boolean isNoTargetSpecified(UUID spaceId, UUID skillId, String skillName) {
        return spaceId == null && skillId == null && (skillName == null || skillName.isBlank());
    }
}
