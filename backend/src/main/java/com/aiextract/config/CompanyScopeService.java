package com.aiextract.config;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.model.Space;
import com.aiextract.model.User;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 企业数据范围服务 — 请求级缓存 + 统一归属校验。
 *
 * <p>company_admin 登录后，所有管理后台接口通过此类校验操作的 entity
 * 是否属于登录用户的企业。同一请求内空间 ID 集合只查一次 DB。</p>
 *
 * <p>super_admin（拥有 COMPANY_MANAGE 权限码）跳过所有校验，可访问全平台数据。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-28
 */
@Service
@RequiredArgsConstructor
public class CompanyScopeService {

    private final UserRepository userRepository;
    private final SpaceRepository spaceRepository;
    private final SkillRepository skillRepository;
    private final ExperienceGrainRepository grainRepository;

    /** 请求级缓存：companyId → 该企业的空间 ID 集合 */
    private static final ThreadLocal<Map<UUID, Set<UUID>>> SPACE_ID_CACHE = ThreadLocal.withInitial(HashMap::new);

    /** 清除请求级缓存（由 TokenContext.clear() 或 Filter 调用） */
    public static void clearCache() {
        SPACE_ID_CACHE.get().clear();
    }

    /** super_admin（拥有 COMPANY_MANAGE）不受企业数据范围限制 */
    private boolean isSuperAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(Permission.COMPANY_MANAGE));
    }

    /**
     * 获取企业下的所有空间 ID。请求级缓存，同一次请求内只查一次 DB。
     *
     * @param companyId 企业 ID
     * @return null 表示不限制（super_admin），空集合表示该企业无空间
     */
    public Set<UUID> getSpaceIds(UUID companyId) {
        if (isSuperAdmin()) return null;
        if (companyId == null) return null;
        Map<UUID, Set<UUID>> cache = SPACE_ID_CACHE.get();
        return cache.computeIfAbsent(companyId, cid -> {
            List<UUID> userIds = userRepository.findByCompanyId(cid).stream()
                    .map(User::getId).toList();
            if (userIds.isEmpty()) return Collections.emptySet();
            return spaceRepository.findByUserIdIn(userIds).stream()
                    .map(Space::getId).collect(Collectors.toSet());
        });
    }

    /**
     * 获取企业下的所有用户 ID。
     */
    public List<UUID> getUserIds(UUID companyId) {
        if (isSuperAdmin()) return null;
        if (companyId == null) return null;
        return userRepository.findByCompanyId(companyId).stream()
                .map(User::getId).toList();
    }

    /**
     * 校验分身属于本企业。
     *
     * @param skillId 分身 ID
     * @throws BusinessException 403 如果分身不属于本企业
     */
    public void assertSkillOwnership(UUID skillId) {
        if (isSuperAdmin()) return;
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) return;
        Set<UUID> spaceIds = getSpaceIds(companyId);
        if (spaceIds.isEmpty()) {
            throw new BusinessException(403, "无权访问其他企业的分身");
        }
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        if (!spaceIds.contains(skill.getSpaceId())) {
            throw new BusinessException(403, "无权访问其他企业的分身");
        }
    }

    /**
     * 校验颗粒属于本企业。
     *
     * @param grainId 颗粒 ID
     * @throws BusinessException 403 如果颗粒不属于本企业
     */
    public void assertGrainOwnership(UUID grainId) {
        if (isSuperAdmin()) return;
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) return;
        Set<UUID> spaceIds = getSpaceIds(companyId);
        if (spaceIds.isEmpty()) {
            throw new BusinessException(403, "无权访问其他企业的颗粒");
        }
        ExperienceGrain grain = grainRepository.findById(grainId)
                .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        if (grain.getSpaceId() == null || !spaceIds.contains(grain.getSpaceId())) {
            throw new BusinessException(403, "无权访问其他企业的颗粒");
        }
    }

    /**
     * 获取本企业所有分身 ID。
     *
     * @return null 表示不限制（super_admin），空列表表示该企业无分身
     */
    public List<UUID> getCompanySkillIds(UUID companyId) {
        if (isSuperAdmin()) return null;
        if (companyId == null) return null;
        Set<UUID> spaceIds = getSpaceIds(companyId);
        if (spaceIds.isEmpty()) return Collections.emptyList();
        return skillRepository.findBySpaceIdIn(new ArrayList<>(spaceIds)).stream()
                .map(Skill::getId).toList();
    }
}
