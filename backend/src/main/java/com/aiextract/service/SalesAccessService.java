package com.aiextract.service;

import com.aiextract.config.Permission;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.FollowUpTask;
import com.aiextract.model.Lead;
import com.aiextract.model.ReviewSession;
import com.aiextract.repository.FollowUpTaskRepository;
import com.aiextract.repository.LeadRepository;
import com.aiextract.repository.ReviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 销售模块的归属断言 —— 跨企业一律 404，不泄露资源是否存在。
 */
@Service
@RequiredArgsConstructor
public class SalesAccessService {

    private final ReviewSessionRepository sessionRepository;
    private final LeadRepository leadRepository;
    private final FollowUpTaskRepository taskRepository;

    public UUID requireCompanyId() {
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(403, "无企业归属，无权访问销售复盘数据");
        }
        return companyId;
    }

    public boolean canViewAll() {
        return hasAuthority(Permission.SALES_REVIEW_VIEW_ALL);
    }

    public boolean canManageLead() {
        return hasAuthority(Permission.SALES_LEAD_MANAGE);
    }

    public void requireLeadManage() {
        if (!canManageLead()) {
            throw new BusinessException(403, "无线索管理权限");
        }
    }

    public boolean hasAuthority(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    public ReviewSession requireSession(UUID id) {
        ReviewSession s = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "复盘不存在或不可访问"));
        if (!s.getCompanyId().equals(requireCompanyId())
                || (!canViewAll() && !s.getReviewerId().equals(TokenContext.get()))) {
            throw new BusinessException(404, "复盘不存在或不可访问");
        }
        return s;
    }

    public Lead requireLead(UUID id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "线索不存在或不可访问"));
        if (!lead.getCompanyId().equals(requireCompanyId())
                || (!canViewAll() && !lead.getOwnerId().equals(TokenContext.get()))) {
            throw new BusinessException(404, "线索不存在或不可访问");
        }
        return lead;
    }

    public FollowUpTask requireTask(UUID id) {
        FollowUpTask task = taskRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "任务不存在或不可访问"));
        if (!task.getCompanyId().equals(requireCompanyId())
                || (!canViewAll() && !task.getOwnerId().equals(TokenContext.get()))) {
            throw new BusinessException(404, "任务不存在或不可访问");
        }
        return task;
    }
}
