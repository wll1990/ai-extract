package com.aiextract.service.skill;

import com.aiextract.config.Permission;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AgentSkill;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 技能归属校验：跨租户一律 404（不泄露存在性）。
 *
 * <p>平台级技能对管理员**只读**开放；写入要求平台特权（COMPANY_MANAGE），
 * 且与 acting 解耦——切到企业视角后仍可管理平台技能。</p>
 */
@Component
public class AgentSkillScopeGuard {

    public UUID effectiveCompanyId() {
        return TokenContext.getCompanyId();
    }

    public boolean isPlatformAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> Permission.COMPANY_MANAGE.equals(a.getAuthority()));
    }

    public AgentSkill requireReadable(AgentSkill skill) {
        if (skill == null) {
            throw notFound();
        }
        if (!skill.isPlatformLevel()) {
            UUID companyId = effectiveCompanyId();
            if (companyId == null || !companyId.equals(skill.getCompanyId())) {
                throw notFound();
            }
        }
        // 平台级：管理端入口已要求 SKILL_MANAGE，任何管理员可只读
        return skill;
    }

    public AgentSkill requireWritable(AgentSkill skill) {
        requireReadable(skill);
        if (skill.isPlatformLevel()) {
            if (!isPlatformAdmin()) {
                throw new BusinessException(403, "平台级技能由平台管理员维护");
            }
        } else {
            UUID companyId = effectiveCompanyId();
            if (companyId == null || !companyId.equals(skill.getCompanyId())) {
                throw notFound();
            }
        }
        return skill;
    }

    public void requirePlatformScope() {
        if (!isPlatformAdmin()) {
            throw new BusinessException(403, "无权管理平台级技能");
        }
    }

    public BusinessException notFound() {
        return new BusinessException(404, "技能不存在");
    }
}
