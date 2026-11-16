package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.dto.IndustryCaseRequest;
import com.aiextract.dto.KnowledgeEntryRequest;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.service.EmployeeSubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一线销售提交知识/案例草稿。审批仍在 /admin/**，此处只允许 pending + 本企业 + 本人。
 */
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class EmployeeSubmissionController {

    private final EmployeeSubmissionService service;

    @GetMapping("/knowledge/submissions")
    public ApiResponse<Map<String, Object>> myKnowledge(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var rows = service.myKnowledge(page, size);
        var content = rows.getContent().stream().map(this::knowledgeMap).toList();
        return ApiResponse.success(PageResponse.of(content, rows, page, size));
    }

    @PostMapping("/knowledge/submissions")
    public ApiResponse<Map<String, Object>> submitKnowledge(@RequestBody KnowledgeEntryRequest req) {
        return ApiResponse.success(knowledgeMap(service.submitKnowledge(req)));
    }

    @PostMapping("/knowledge/submissions/{id}/withdraw")
    public ApiResponse<Map<String, Object>> withdrawKnowledge(@PathVariable UUID id) {
        return ApiResponse.success(knowledgeMap(service.withdrawKnowledge(id)));
    }

    @GetMapping("/cases/submissions")
    public ApiResponse<Map<String, Object>> myCases(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var rows = service.myCases(page, size);
        var content = rows.getContent().stream().map(this::caseMap).toList();
        return ApiResponse.success(PageResponse.of(content, rows, page, size));
    }

    @PostMapping("/cases/submissions")
    public ApiResponse<Map<String, Object>> submitCase(@RequestBody IndustryCaseRequest req) {
        return ApiResponse.success(caseMap(service.submitCase(req)));
    }

    @PostMapping("/cases/submissions/{id}/withdraw")
    public ApiResponse<Map<String, Object>> withdrawCase(@PathVariable UUID id) {
        return ApiResponse.success(caseMap(service.withdrawCase(id)));
    }

    private Map<String, Object> knowledgeMap(KnowledgeEntry k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId().toString());
        m.put("title", k.getTitle());
        m.put("category", k.getCategory());
        m.put("status", k.getStatus());
        m.put("createdAt", k.getCreatedAt());
        m.put("updatedAt", k.getUpdatedAt());
        return m;
    }

    private Map<String, Object> caseMap(IndustryCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId().toString());
        m.put("title", c.getTitle());
        m.put("sceneTag", c.getSceneTag());
        m.put("result", c.getResult());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }
}
