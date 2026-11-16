package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.model.Skill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 知识/案例检索范围：企业私有知识绝不出企业。
 *
 * <ul>
 *   <li>调用者企业与分身企业一致：检索分身企业私有库 + 公共库；</li>
 *   <li>跨企业调用者、匿名/C 端：只检索公共库（companyId=null）；</li>
 *   <li>分身无企业归属（C 端专家）：只检索公共库。</li>
 * </ul>
 *
 * <p>经验颗粒不在此范围：它属于分身自身内容，发布分身即分享该分身经验。</p>
 */
@Component
@RequiredArgsConstructor
public class KcScopeResolver {

    private final CompanyScopeService companyScopeService;

    /**
     * @param companyId      传给知识/案例检索的企业 ID；null 表示只查公共库
     * @param skillCompanyId 分身归属企业，用于引用日志与审计
     * @param external       是否为跨企业/匿名调用
     */
    public record KcScope(UUID companyId, UUID skillCompanyId, boolean external) {
    }

    public KcScope resolve(Skill skill, UUID callerCompanyId) {
        UUID skillCompanyId = companyScopeService.resolveSkillCompanyId(skill);
        boolean sameCompany = skillCompanyId != null && skillCompanyId.equals(callerCompanyId);
        return new KcScope(sameCompany ? skillCompanyId : null, skillCompanyId,
                skillCompanyId != null && !sameCompany);
    }

    public KcScope forCompany(UUID companyId) {
        return new KcScope(companyId, companyId, false);
    }
}
