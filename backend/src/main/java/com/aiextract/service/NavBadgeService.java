package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.repository.ReviewCoachRequestRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.aiextract.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 导航待办红点 — 让用户不点进页面就知道哪个域、哪一项有活。
 *
 * <p>只做 COUNT，不加载实体；企业范围复用 {@link CompanyScopeService} 的请求级缓存。
 * super_admin 没有企业归属，平台级待办不在这里统计，直接返回空。</p>
 */
@Service
@RequiredArgsConstructor
public class NavBadgeService {

    /** 需要人工处理的素材状态：待解析、待清洗、清洗/萃取中 */
    private static final List<String> PENDING_MATERIAL_STATUSES = List.of(
            "uploaded", "parse_failed", "parsed", "cleaning_failed", "cleaning", "analyzing");

    private static final List<String> PENDING_SKILL_STATUSES = List.of("reviewing", "generating");

    private final CompanyScopeService companyScopeService;
    private final SkillRepository skillRepository;
    private final SkillMaterialRepository materialRepository;
    private final ReviewCoachRequestRepository coachRequestRepository;

    /**
     * @return {"domains": {domainKey: count}, "items": {path: count}}
     */
    public Map<String, Object> getBadges() {
        UUID companyId = TokenContext.getCompanyId();
        UUID userId = TokenContext.get();
        Map<String, Long> domains = new LinkedHashMap<>();
        Map<String, Long> items = new LinkedHashMap<>();

        if (companyId != null && !companyScopeService.isUnrestrictedDataScope()) {
            Set<UUID> spaceIdSet = companyScopeService.getSpaceIds(companyId);
            if (!spaceIdSet.isEmpty()) {
                List<UUID> spaceIds = List.copyOf(spaceIdSet);
                long pendingSkills = skillRepository
                        .countByStatusInAndSpaceIdIn(PENDING_SKILL_STATUSES, spaceIds);
                List<UUID> skillIds = skillRepository.findIdsBySpaceIdIn(spaceIds);
                long pendingMaterials = skillIds.isEmpty() ? 0
                        : materialRepository.countByStatusInAndSkillIdIn(PENDING_MATERIAL_STATUSES, skillIds);
                if (pendingSkills > 0) {
                    items.put("/admin/skills", pendingSkills);
                }
                if (pendingMaterials > 0) {
                    items.put("/admin/materials", pendingMaterials);
                }
                long production = pendingSkills + pendingMaterials;
                if (production > 0) {
                    domains.put("production", production);
                }
            }

            long pendingCoach = userId == null ? 0
                    : coachRequestRepository.countByCompanyIdAndCoachUserIdAndStatus(companyId, userId, "pending");
            if (pendingCoach > 0) {
                items.put("/sales/coach/ledger", pendingCoach);
                domains.put("sales", pendingCoach);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domains", domains);
        result.put("items", items);
        return result;
    }
}
