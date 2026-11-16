package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Lead;
import com.aiextract.model.LeadActivity;
import com.aiextract.repository.LeadActivityRepository;
import com.aiextract.repository.LeadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 线索时间线写入入口。与业务同事务；actor_id 为空表示系统动作。
 */
@Service
@RequiredArgsConstructor
public class LeadActivityService {

    private final LeadRepository leadRepository;
    private final LeadActivityRepository activityRepository;

    @Transactional
    public LeadActivity record(UUID leadId, String type, String content,
                               String sourceType, UUID sourceId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new BusinessException(404, "线索不存在"));
        LocalDateTime now = LocalDateTime.now();
        LeadActivity activity = LeadActivity.builder()
                .id(UUID.randomUUID())
                .companyId(lead.getCompanyId())
                .leadId(leadId)
                .type(type)
                .content(content)
                .actorId(TokenContext.get())
                .sourceType(sourceType)
                .sourceId(sourceId)
                .occurredAt(now)
                .build();
        activityRepository.save(activity);
        lead.setLastActivityAt(now);
        leadRepository.save(lead);
        return activity;
    }

    /** 系统动作（Scheduler）：没有登录用户 */
    @Transactional
    public LeadActivity recordSystem(UUID leadId, String type, String content,
                                     String sourceType, UUID sourceId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new BusinessException(404, "线索不存在"));
        LocalDateTime now = LocalDateTime.now();
        LeadActivity activity = LeadActivity.builder()
                .id(UUID.randomUUID())
                .companyId(lead.getCompanyId())
                .leadId(leadId)
                .type(type)
                .content(content)
                .actorId(null)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .occurredAt(now)
                .build();
        activityRepository.save(activity);
        lead.setLastActivityAt(now);
        leadRepository.save(lead);
        return activity;
    }
}
