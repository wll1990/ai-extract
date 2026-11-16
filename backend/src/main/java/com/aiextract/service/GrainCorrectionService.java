package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AdminAuditLog;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.GrainCorrectionSuggestion;
import com.aiextract.model.Skill;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.GrainCorrectionSuggestionRepository;
import com.aiextract.repository.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 颗粒纠错建议 — 员工提交、管理员审核（GR-06）。
 *
 * <p>员工不直接写颗粒：提交的建议先进入 pending 队列，管理员通过后才覆盖字段并重算向量，
 * 避免未经审核的内容进入 RAG 检索。</p>
 *
 * <p>向量刷新在事务外执行（照 {@link AdminGrainService#refreshEmbedding}），
 * 事务只包裹 DB 更新。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrainCorrectionService {

    /** 自注入代理：审核落库走短事务，向量刷新在事务外 */
    @Autowired
    @Lazy
    private GrainCorrectionService self;

    private final GrainCorrectionSuggestionRepository repository;
    private final ExperienceGrainRepository grainRepository;
    private final SkillRepository skillRepository;
    private final CompanyScopeService companyScopeService;
    private final AdminGrainService adminGrainService;
    private final AdminAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /** 员工提交纠错建议。 */
    @Transactional(rollbackFor = Exception.class)
    public GrainCorrectionSuggestion submit(UUID skillId, UUID grainId, Map<String, String> body) {
        companyScopeService.requireSkillReadable(skillId);
        UUID userId = TokenContext.get();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        ExperienceGrain grain = grainRepository.findById(grainId)
                .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        if (!belongsToSkill(skill, grain)) {
            throw new BusinessException(404, "颗粒不属于该分身");
        }
        String reason = trim(body.get("reason"));
        if (reason == null) {
            throw new BusinessException(400, "请填写问题原因");
        }
        if (reason.length() > 2000) {
            throw new BusinessException(400, "问题原因不能超过 2000 字");
        }
        UUID companyId = skill.getCompanyId() != null ? skill.getCompanyId() : TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(400, "无法确定企业归属");
        }
        if (repository.findFirstByGrainIdAndSuggestedByAndStatusOrderByCreatedAtDesc(
                grainId, userId, GrainCorrectionSuggestion.STATUS_PENDING).isPresent()) {
            throw new BusinessException(409, "你已经提交过这条颗粒的纠错建议，请等待审核");
        }
        GrainCorrectionSuggestion suggestion = GrainCorrectionSuggestion.builder()
                .id(UUID.randomUUID())
                .grainId(grainId)
                .skillId(skillId)
                .companyId(companyId)
                .suggestedBy(userId)
                .reason(reason)
                .sceneDescription(limit(body.get("sceneDescription"), 5000))
                .expertThought(limit(body.get("expertThought"), 5000))
                .standardScript(limit(body.get("standardScript"), 5000))
                .commonMistakes(limit(body.get("commonMistakes"), 5000))
                .status(GrainCorrectionSuggestion.STATUS_PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        repository.save(suggestion);
        return suggestion;
    }

    /** 管理端列表：company_admin 只看本企业，super_admin 看全部。 */
    @Transactional(readOnly = true)
    public Page<GrainCorrectionSuggestion> list(String status, int page, int size) {
        Pageable p = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100));
        String st = trim(status);
        if (companyScopeService.isUnrestrictedDataScope()) {
            return st == null
                    ? repository.findAllByOrderByCreatedAtDesc(p)
                    : repository.findByStatusOrderByCreatedAtDesc(st, p);
        }
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(403, "缺少企业信息");
        }
        return st == null
                ? repository.findByCompanyIdOrderByCreatedAtDesc(companyId, p)
                : repository.findByCompanyIdAndStatusOrderByCreatedAtDesc(companyId, st, p);
    }

    /** 审核通过：覆盖颗粒字段并标记建议已处理，向量刷新在事务外。 */
    public GrainCorrectionSuggestion approve(UUID id, String note) {
        GrainCorrectionSuggestion applied = self.applyApprove(id, note);
        adminGrainService.refreshEmbedding(applied.getGrainId());
        return applied;
    }

    @Transactional(rollbackFor = Exception.class)
    public GrainCorrectionSuggestion applyApprove(UUID id, String note) {
        GrainCorrectionSuggestion s = requirePending(id);
        ExperienceGrain grain = grainRepository.findById(s.getGrainId())
                .orElseThrow(() -> new BusinessException(404, "颗粒已不存在"));
        if (s.getSceneDescription() != null) grain.setSceneDescription(s.getSceneDescription());
        if (s.getExpertThought() != null) grain.setExpertThought(s.getExpertThought());
        if (s.getStandardScript() != null) grain.setStandardScript(s.getStandardScript());
        if (s.getCommonMistakes() != null) grain.setCommonMistakes(s.getCommonMistakes());
        grainRepository.save(grain);

        s.setStatus(GrainCorrectionSuggestion.STATUS_APPROVED);
        s.setReviewedBy(TokenContext.get());
        s.setReviewedAt(LocalDateTime.now());
        s.setReviewNote(limit(note, 1000));
        repository.save(s);
        writeAudit("approve_grain_correction", s.getId(), Map.of("grainId", s.getGrainId().toString()));
        return s;
    }

    /** 审核拒绝：只标记状态，不改颗粒。 */
    @Transactional(rollbackFor = Exception.class)
    public GrainCorrectionSuggestion reject(UUID id, String note) {
        GrainCorrectionSuggestion s = requirePending(id);
        s.setStatus(GrainCorrectionSuggestion.STATUS_REJECTED);
        s.setReviewedBy(TokenContext.get());
        s.setReviewedAt(LocalDateTime.now());
        s.setReviewNote(limit(note, 1000));
        repository.save(s);
        writeAudit("reject_grain_correction", s.getId(), Map.of("grainId", s.getGrainId().toString()));
        return s;
    }

    /** 本企业待审数量（导航红点/工作台用）。 */
    @Transactional(readOnly = true)
    public long pendingCount() {
        UUID companyId = TokenContext.getCompanyId();
        return companyId == null ? 0 : repository.countByCompanyIdAndStatus(
                companyId, GrainCorrectionSuggestion.STATUS_PENDING);
    }

    private GrainCorrectionSuggestion requirePending(UUID id) {
        GrainCorrectionSuggestion s = repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "纠错建议不存在"));
        if (!companyScopeService.isUnrestrictedDataScope()) {
            UUID companyId = TokenContext.getCompanyId();
            if (companyId == null || !companyId.equals(s.getCompanyId())) {
                throw new BusinessException(404, "纠错建议不存在");
            }
        }
        if (!GrainCorrectionSuggestion.STATUS_PENDING.equals(s.getStatus())) {
            throw new BusinessException(409, "该建议已处理");
        }
        return s;
    }

    /**
     * 颗粒是否属于分身：个人分身按 space 判断；
     * 组织分身按 memberSkillIds 里的成员分身 space 判断。
     */
    private boolean belongsToSkill(Skill skill, ExperienceGrain grain) {
        if (skill.getSpaceId() != null && skill.getSpaceId().equals(grain.getSpaceId())) {
            return true;
        }
        if (!"organization".equals(skill.getType()) || skill.getMemberSkillIds() == null) {
            return false;
        }
        try {
            List<UUID> memberIds = new ArrayList<>();
            for (var node : objectMapper.readTree(skill.getMemberSkillIds())) {
                memberIds.add(UUID.fromString(node.asText()));
            }
            return skillRepository.findAllById(memberIds).stream()
                    .anyMatch(m -> m.getSpaceId() != null && m.getSpaceId().equals(grain.getSpaceId()));
        } catch (Exception e) {
            log.warn("解析组织分组成员失败 skillId={}: {}", skill.getId(), e.getMessage());
            return false;
        }
    }

    private String trim(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private String limit(String value, int max) {
        String v = trim(value);
        if (v == null) return null;
        if (v.length() > max) {
            throw new BusinessException(400, "内容过长，最多 " + max + " 字");
        }
        return v;
    }

    private void writeAudit(String action, UUID targetId, Map<String, ?> detail) {
        try {
            UUID adminId = TokenContext.get();
            auditLogRepository.save(AdminAuditLog.builder()
                    .id(UUID.randomUUID())
                    .adminId(adminId != null ? adminId
                            : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                    .action(action)
                    .targetType("grain_correction")
                    .targetId(targetId)
                    .detail(objectMapper.writeValueAsString(detail))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.debug("审计日志写入失败: {}", e.getMessage());
        }
    }
}
