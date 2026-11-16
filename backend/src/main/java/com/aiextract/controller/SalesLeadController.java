package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.dto.LeadActivityView;
import com.aiextract.dto.LeadContactRequest;
import com.aiextract.dto.LeadContactView;
import com.aiextract.dto.LeadDetailView;
import com.aiextract.dto.LeadNoteRequest;
import com.aiextract.dto.LeadSummary;
import com.aiextract.dto.LeadUpdateRequest;
import com.aiextract.dto.StatusTransitionRequest;
import com.aiextract.service.SalesLeadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/sales/leads")
@RequiredArgsConstructor
public class SalesLeadController {

    private final SalesLeadService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(PageResponse.of(service.list(page, size, status), page, size));
    }

    /** 可分配负责人列表（线索分配下拉） */
    @GetMapping("/owners")
    public ApiResponse<List<Map<String, Object>>> owners() {
        return ApiResponse.success(service.listAssignableOwners());
    }

    @GetMapping("/{id}")
    public ApiResponse<LeadDetailView> detail(@PathVariable UUID id) {
        return ApiResponse.success(service.detail(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<LeadSummary> update(@PathVariable UUID id,
                                           @Valid @RequestBody LeadUpdateRequest req) {
        return ApiResponse.success(service.update(id, req));
    }

    /** 分配线索负责人（仅 SALES_LEAD_MANAGE） */
    @PutMapping("/{id}/owner")
    public ApiResponse<LeadSummary> assign(@PathVariable UUID id,
                                           @RequestBody Map<String, String> body) {
        String ownerId = body.get("ownerId");
        if (ownerId == null || ownerId.isBlank()) {
            throw new com.aiextract.exception.BusinessException(400, "请选择负责人");
        }
        return ApiResponse.success(service.assign(id, UUID.fromString(ownerId)));
    }

    @GetMapping("/{id}/activities")
    public ApiResponse<Map<String, Object>> activities(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(PageResponse.of(
                service.activities(id, page, size), page, size));
    }

    @PostMapping("/{id}/notes")
    public ApiResponse<LeadActivityView> note(@PathVariable UUID id,
                                              @Valid @RequestBody LeadNoteRequest req) {
        return ApiResponse.success(service.addNote(id, req.content()));
    }

    @PostMapping("/{id}/contacts")
    public ApiResponse<LeadContactView> addContact(@PathVariable UUID id,
                                                   @Valid @RequestBody LeadContactRequest req) {
        return ApiResponse.success(service.addContact(id, req));
    }

    @PutMapping("/{id}/contacts/{contactId}")
    public ApiResponse<LeadContactView> updateContact(
            @PathVariable UUID id, @PathVariable UUID contactId,
            @Valid @RequestBody LeadContactRequest req) {
        return ApiResponse.success(service.updateContact(id, contactId, req));
    }

    @DeleteMapping("/{id}/contacts/{contactId}")
    public ApiResponse<Void> deleteContact(@PathVariable UUID id, @PathVariable UUID contactId) {
        service.deleteContact(id, contactId);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/status")
    public ApiResponse<LeadSummary> updateStatus(
            @PathVariable UUID id, @Valid @RequestBody StatusTransitionRequest req) {
        return ApiResponse.success(service.updateStatus(id, req.status()));
    }
}
