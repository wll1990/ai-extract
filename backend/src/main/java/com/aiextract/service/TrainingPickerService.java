package com.aiextract.service;

import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 培训选择器 —— 统一 keyword/status/role/分页，替代前端「拉 100 条本地过滤」。
 *
 * <p>五类 kind：users / knowledge / cases / skills / datasets。
 * 范围一律限定在当前企业（知识/题集允许公共资源），不做跨租户搜索。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Service
@RequiredArgsConstructor
public class TrainingPickerService {

    private final UserRepository userRepository;
    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final SkillRepository skillRepository;
    private final EvaluationDatasetRepository datasetRepository;
    private final TrainingAccessService accessService;
    private final com.aiextract.config.CompanyScopeService companyScopeService;

    public record PickerItem(String id, String name, String subtitle, String status) {
    }

    /** 只有 B 端角色可以当学员/导师/复核人；C 端用户即使挂在企业下也不能被分配 */
    private static final List<String> B_END_ROLES =
            List.of("employee", "company_admin", "super_admin");

    public Page<PickerItem> pick(String kind, String keyword, String status, String role,
                                 boolean coach, int page, int size) {
        UUID companyId = accessService.requireCompanyId();
        boolean superAdmin = companyScopeService.isUnrestrictedDataScope();
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        PageRequest pageable = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 50));
        List<String> roles;
        if (role == null || role.isBlank()) {
            roles = B_END_ROLES;
        } else if (B_END_ROLES.contains(role)) {
            roles = List.of(role);
        } else {
            throw new com.aiextract.exception.BusinessException(400, "不支持的角色筛选：" + role);
        }
        return switch (kind) {
            case "users" -> (superAdmin
                    ? userRepository.searchForPickerAll(roles, kw, pageable)
                    : userRepository.searchForPicker(companyId, roles, kw, pageable))
                    .map(u -> new PickerItem(u.getId().toString(), u.getName(),
                            u.getAccount(), u.getRole()));
            case "knowledge" -> knowledgeRepository.search(superAdmin, companyId, null, !superAdmin, null, null,
                            status == null || status.isBlank() ? "active" : status, kw, false, false, false, pageable)
                    .map(k -> new PickerItem(k.getId().toString(), k.getTitle(),
                            k.getCategory(), k.getStatus()));
            case "cases" -> caseRepository.search(superAdmin, companyId, null, !superAdmin, null, null, null,
                            status == null || status.isBlank() ? "active" : status, kw, false, false, false, pageable)
                    .map(c -> new PickerItem(c.getId().toString(), c.getTitle(),
                            c.getSceneTag(), c.getStatus()));
            case "skills" -> (coach
                    ? skillRepository.searchCoaches(companyId,
                            status == null || status.isBlank() ? "published" : status, kw, pageable)
                    : (superAdmin
                        ? skillRepository.searchForPickerAll(
                                status == null || status.isBlank() ? "published" : status, kw, pageable)
                        : skillRepository.searchForPicker(companyId,
                                status == null || status.isBlank() ? "published" : status, kw, pageable)))
                    .map(s -> new PickerItem(s.getId().toString(),
                            s.getDisplayName() != null ? s.getDisplayName() : s.getOwnerName(),
                            s.getOwnerName(), s.getStatus()));
            case "datasets" -> datasetRepository.search(superAdmin, companyId, null,
                            status == null || status.isBlank() ? "published" : status, kw, pageable)
                    .map(d -> new PickerItem(d.getId().toString(), d.getName(),
                            "v" + d.getCurrentVersion(), d.getStatus()));
            default -> throw new com.aiextract.exception.BusinessException(400,
                    "未知的选择器类型：" + kind);
        };
    }
}
