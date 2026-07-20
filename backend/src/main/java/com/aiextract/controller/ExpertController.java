package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.*;
import com.aiextract.model.ExpertDocument;
import com.aiextract.service.ExpertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;


/**
 * 萃取师专家经验库控制器
 *
 * <p>实现全部13个API接口，覆盖萃取师经验的上传、提取、
 * 审核、激活、文档管理和综合Skill生成。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ExpertController {

    private final ExpertService expertService;

    /** ---- 公开接口 ---- */
    @GetMapping("/experts/available")
    public ApiResponse<List<ExpertAvailableResponse>> getAvailableExperts(
            @RequestParam(required = false) String domain) {
        return ApiResponse.success(expertService.getAvailableExperts(domain));
    }

    /** ---- 管理接口 ---- */
    @GetMapping("/admin/experts")
    public ApiResponse<Page<ExpertSkillListResponse>> getExperts(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String status) {
        return ApiResponse.success(expertService.getExperts(page, size, keyword, status));
    }

    @GetMapping("/admin/experts/{expertId}")
    public ApiResponse<ExpertSkillDetailResponse> getExpertDetail(@PathVariable String expertId) {
        return ApiResponse.success(expertService.getExpertDetail(expertId));
    }

    @PostMapping("/admin/experts/upload")
    public ApiResponse<ExpertSkillDetailResponse> uploadExpertMaterials(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        @SuppressWarnings("unchecked") List<String> styleTags = (List<String>) body.get("styleTags");
        @SuppressWarnings("unchecked") List<String> industryTags = (List<String>) body.get("industryTags");
        String seniority = (String) body.get("seniority");
        @SuppressWarnings("unchecked") List<Map<String, Object>> files = (List<Map<String, Object>>) body.get("files");
        String domain = (String) body.get("domain");
        String existingExpertId = (String) body.get("existingExpertId");
        return ApiResponse.success(expertService.uploadExpertMaterials(name, description, styleTags, industryTags, seniority, files, domain, existingExpertId));
    }

    /** 上传文档文件字节（multipart），返回创建的 ExpertDocument */
    @PostMapping("/admin/experts/{expertId}/documents/file")
    public ApiResponse<ExpertDocument> uploadDocumentFile(@PathVariable String expertId,
                                                           @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(expertService.uploadDocumentFile(UUID.fromString(expertId), file));
    }

    @PostMapping("/admin/experts/{expertId}/extract")
    public ApiResponse<Void> extractGrains(@PathVariable String expertId) {
        expertService.extractGrains(expertId);
        return ApiResponse.success();
    }

    @PutMapping("/admin/experts/{expertId}/grains/{grainId}")
    public ApiResponse<Void> editGrain(@PathVariable String expertId, @PathVariable String grainId,
                                        @RequestBody ExpertGrainEditRequest request) {
        expertService.editGrain(expertId, grainId, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/admin/experts/{expertId}")
    public ApiResponse<Void> deleteExpert(@PathVariable String expertId) {
        expertService.deleteExpert(expertId);
        return ApiResponse.success();
    }

    @DeleteMapping("/admin/experts/{expertId}/grains/{grainId}")
    public ApiResponse<Void> deleteGrain(@PathVariable String expertId, @PathVariable String grainId) {
        expertService.deleteGrain(expertId, grainId);
        return ApiResponse.success();
    }

    @PostMapping("/admin/experts/{expertId}/activate")
    public ApiResponse<Void> activateExpert(@PathVariable String expertId) {
        expertService.activateExpert(expertId);
        return ApiResponse.success();
    }

    @PostMapping("/admin/experts/{expertId}/documents")
    public ApiResponse<Void> addDocuments(@PathVariable String expertId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> files = (List<Map<String, Object>>) body.get("files");
        expertService.addDocuments(expertId, files);
        return ApiResponse.success();
    }

    @DeleteMapping("/admin/experts/{expertId}/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String expertId, @PathVariable String documentId) {
        expertService.deleteDocument(expertId, documentId);
        return ApiResponse.success();
    }

    @PutMapping("/admin/experts/{expertId}/documents/{documentId}")
    public ApiResponse<Void> replaceDocument(@PathVariable String expertId, @PathVariable String documentId,
                                              @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") Map<String, Object> file = (Map<String, Object>) body.get("file");
        expertService.replaceDocument(expertId, documentId, file);
        return ApiResponse.success();
    }

    /** 手动触发萃取师AI分析（用于调试和验证） */
    @PostMapping("/admin/experts/{expertId}/retry")
    public ApiResponse<Void> retryExpert(@PathVariable String expertId) {
        expertService.retryExpert(expertId);
        return ApiResponse.success();
    }

    @PostMapping("/admin/experts/{expertId}/analyze")
    public ApiResponse<Void> manualAnalyze(@PathVariable String expertId) {
        expertService.analyzeMaterials(java.util.UUID.fromString(expertId));
        return ApiResponse.success();
    }

    @PostMapping("/admin/experts/composite/regenerate")
    public ApiResponse<Void> regenerateComposite() {
        expertService.regenerateComposite();
        return ApiResponse.success();
    }

    @GetMapping("/admin/experts/composite")
    public ApiResponse<ExpertCompositeResponse> getCompositeDetail() {
        return ApiResponse.success(expertService.getCompositeDetail());
    }

    /**
     * 更新文档内容（手动处理图片/音频后填入）
     */
    @PutMapping("/admin/experts/documents/{docId}")
    public ApiResponse<Void> updateDocument(
            @PathVariable String docId,
            @RequestBody Map<String, String> body) {
        expertService.updateDocumentContent(docId,
                body.get("parsedContent"),
                body.getOrDefault("status", "parsed"));
        return ApiResponse.success();
    }
}
