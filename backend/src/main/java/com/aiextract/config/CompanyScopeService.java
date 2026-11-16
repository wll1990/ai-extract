package com.aiextract.config;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.Skill;
import com.aiextract.model.Space;
import com.aiextract.model.User;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationRunRepository;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final IndustryCaseRepository caseRepository;
    private final EvaluationDatasetRepository evaluationDatasetRepository;
    private final EvaluationRunRepository evaluationRunRepository;

    /** 请求级缓存：companyId → 该企业的空间 ID 集合 */
    private static final ThreadLocal<Map<UUID, Set<UUID>>> SPACE_ID_CACHE = ThreadLocal.withInitial(HashMap::new);

    /** 请求级缓存：spaceId → 空间属主企业（含 null，用 Optional 包装） */
    private static final ThreadLocal<Map<UUID, Optional<UUID>>> SPACE_COMPANY_CACHE =
            ThreadLocal.withInitial(HashMap::new);

    /** 清除请求级缓存（由 TokenContext.clear() 或 Filter 调用） */
    public static void clearCache() {
        SPACE_ID_CACHE.get().clear();
        SPACE_COMPANY_CACHE.get().clear();
    }

    /**
     * 分身归属企业：organization 分身直接用 companyId；individual 分身走 space → user。
     * C 端专家（无企业归属）返回 null，调用方据此只检索公共库。
     */
    public UUID resolveSkillCompanyId(Skill skill) {
        if (skill == null) {
            return null;
        }
        if (skill.getCompanyId() != null) {
            return skill.getCompanyId();
        }
        UUID spaceId = skill.getSpaceId();
        if (spaceId == null) {
            return null;
        }
        return SPACE_COMPANY_CACHE.get().computeIfAbsent(spaceId, id ->
                spaceRepository.findById(id)
                        .map(Space::getUserId)
                        .flatMap(userRepository::findById)
                        .map(User::getCompanyId)).orElse(null);
    }

    public UUID resolveSkillCompanyId(UUID skillId) {
        return skillId == null ? null : skillRepository.findById(skillId)
                .map(this::resolveSkillCompanyId).orElse(null);
    }

    /** super_admin（拥有 COMPANY_MANAGE）不受企业数据范围限制 */
    public boolean isSuperAdmin() {
        return isSuperAdminStatic();
    }

    /** 静态版本，供不持有本 Bean 的场景（如 AuthService 的账号管理）使用 */
    public static boolean isSuperAdminStatic() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(Permission.COMPANY_MANAGE));
    }

    /**
     * 是否拥有「不受限数据范围」：平台超管且未切换到具体企业视角。
     *
     * <p>与 {@link #isSuperAdmin()} 的区别是语义：前者回答「能看全平台数据吗」，
     * 后者回答「是否拥有平台特权」。超管切换到某企业后，数据范围收敛到该企业，
     * 但平台特权（企业/合作方/维护类操作）保持不变。</p>
     */
    public boolean isUnrestrictedDataScope() {
        return isSuperAdminStatic() && TokenContext.getActingCompanyId() == null;
    }

    /**
     * 非抛异常的分身可读判断，供批量接口逐项过滤。
     *
     * <p>批量接口里不能对每条记录调用抛异常的 assert，否则一条越权会让整个响应失败。</p>
     */
    public boolean canAccessSkill(UUID skillId) {
        if (skillId == null) {
            return false;
        }
        if (isUnrestrictedDataScope()) {
            return true;
        }
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            return false;
        }
        // 组织分身 spaceId 为空，只能按 companyId 判断（getCompanySkillIds 是空间维度，覆盖不到）
        var skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) {
            return false;
        }
        if (skill.getSpaceId() == null) {
            return companyId.equals(skill.getCompanyId());
        }
        return getCompanySkillIds(companyId).contains(skillId);
    }

    /**
     * 获取指定企业下的所有空间 ID。请求级缓存，同一次请求内只查一次 DB。
     *
     * <p>平台超管切换到具体企业视角时同样返回该企业的空间；只有未指定企业（companyId=null）
     * 才返回空集合。需要「不限制」语义的调用方必须先自行判断 {@link #isUnrestrictedDataScope()}。</p>
     *
     * @param companyId 企业 ID
     * @return 空集合表示未指定企业或该企业无空间
     */
    public Set<UUID> getSpaceIds(UUID companyId) {
        if (companyId == null) return Collections.emptySet();
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
        if (isUnrestrictedDataScope() || companyId == null) return Collections.emptyList();
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
        if (isUnrestrictedDataScope()) return;
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        assertSkillOwnership(skill);
    }

    /**
     * 分身可读性守卫 —— 详情/对话/场景标签等「按 ID 直读」入口统一走这里。
     *
     * <p>已发布分身对所有登录用户可见（与 /public/skills 公开目录一致）；
     * 未发布（generating/reviewing/discarded）只允许归属企业或空间属主读取，防止跨租户读草稿。</p>
     *
     * @return 已加载的 Skill，避免调用方重复查询
     * @throws BusinessException 404 分身不存在 / 403 非归属方读取未发布分身
     */
    public Skill requireSkillReadable(UUID skillId) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        if (!"published".equals(skill.getStatus())) {
            assertSkillOwnership(skill);
        }
        return skill;
    }

    private void assertSkillOwnership(Skill skill) {
        if (isUnrestrictedDataScope()) return;
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            // C 端 token 不带 companyId：必须按空间属主校验，不能静默放行
            UUID userId = TokenContext.get();
            if (userId == null || skill.getSpaceId() == null) {
                throw new BusinessException(403, "无权访问该分身");
            }
            var space = spaceRepository.findById(skill.getSpaceId())
                    .orElseThrow(() -> new BusinessException(404, "空间不存在"));
            if (!space.isOwnedBy(userId)) {
                throw new BusinessException(403, "无权访问该分身");
            }
            return;
        }
        // 组织分身没有 spaceId，按 companyId 直接校验
        if (skill.getSpaceId() == null) {
            if (!Objects.equals(companyId, skill.getCompanyId())) {
                throw new BusinessException(403, "无权访问其他企业的分身");
            }
            return;
        }
        Set<UUID> spaceIds = getSpaceIds(companyId);
        if (spaceIds.isEmpty() || !spaceIds.contains(skill.getSpaceId())) {
            throw new BusinessException(403, "无权访问其他企业的分身");
        }
    }

    /**
     * 校验空间属于本企业。super_admin 跳过企业范围限制，但仍校验空间存在。
     *
     * @param spaceId 空间 ID
     * @throws BusinessException 404 空间不存在；403 不属于本企业
     */
    public void assertSpaceOwnership(UUID spaceId) {
        Space space = spaceRepository.findById(spaceId)
                .orElseThrow(() -> new BusinessException(404, "空间不存在"));
        if (isUnrestrictedDataScope()) {
            return;
        }
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(403, "无企业归属，无权访问空间");
        }
        Set<UUID> spaceIds = getSpaceIds(companyId);
        if (!spaceIds.contains(space.getId())) {
            throw new BusinessException(403, "无权访问其他企业的空间");
        }
    }

    /**
     * 校验颗粒属于本企业。
     *
     * @param grainId 颗粒 ID
     * @throws BusinessException 403 如果颗粒不属于本企业
     */
    public void assertGrainOwnership(UUID grainId) {
        if (isUnrestrictedDataScope()) return;
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(403, "无企业归属，无权访问颗粒");
        }
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
        if (isUnrestrictedDataScope() || companyId == null) return Collections.emptyList();
        Set<UUID> spaceIds = getSpaceIds(companyId);
        if (spaceIds.isEmpty()) return Collections.emptyList();
        return skillRepository.findBySpaceIdIn(new ArrayList<>(spaceIds)).stream()
                .map(Skill::getId).toList();
    }

    /** 校验知识条目可读并返回实体：super_admin 全可读；企业管理员可读「本企业 + 已生效公共」。 */
    public KnowledgeEntry requireKnowledgeReadable(UUID id) {
        return requireKnowledgeScope(id, true, "知识");
    }

    /** 校验知识条目可写：super_admin 全可写；企业管理员只能写本企业知识（公共知识不可直接改）。 */
    public void assertKnowledgeWritable(UUID id) {
        requireKnowledgeScope(id, false, "知识");
    }

    /** 兼容旧调用：只校验不返回实体。 */
    public void assertKnowledgeReadable(UUID id) {
        requireKnowledgeScope(id, true, "知识");
    }

    /**
     * 判断个人分身是否属于指定企业：优先 company_id，历史数据回退 space → user。
     * 用于组织分身成员归属校验，避免把其他企业的分身挂进来。
     */
    public boolean skillBelongsToCompany(Skill skill, UUID companyId) {
        if (skill == null || companyId == null) {
            return false;
        }
        if (skill.getCompanyId() != null) {
            return companyId.equals(skill.getCompanyId());
        }
        if (skill.getSpaceId() == null) {
            return false;
        }
        return spaceRepository.findById(skill.getSpaceId())
                .flatMap(space -> userRepository.findById(space.getUserId()))
                .map(user -> companyId.equals(user.getCompanyId()))
                .orElse(false);
    }

    /** 校验案例条目可读并返回实体。 */
    public IndustryCase requireCaseReadable(UUID id) {
        return requireCaseScope(id, true, "案例");
    }

    /** 兼容旧调用：只校验不返回实体。 */
    public void assertCaseReadable(UUID id) {
        requireCaseScope(id, true, "案例");
    }

    /**
     * 培训任务发布前校验知识可用：存在、可读、active 且未过期。
     *
     * <p>发布时不拦，学员打开阅读任务才报 409「已过期」，用户只会看到整页错误。
     * 所以在发布门槛上提前暴露，并带上任务标题便于定位。</p>
     */
    public void assertKnowledgeUsable(UUID id, String taskTitle) {
        KnowledgeEntry entry = requireKnowledgeReadable(id);
        if (!"active".equals(entry.getStatus())) {
            throw new BusinessException(400,
                    "任务「" + taskTitle + "」引用的知识已下线，请更换后再发布");
        }
        if (entry.getExpireAt() != null && entry.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400,
                    "任务「" + taskTitle + "」引用的知识已过期，请更换后再发布");
        }
    }

    /** 培训任务发布前校验案例可用：存在、可读、active 且未过期。 */
    public void assertCaseUsable(UUID id, String taskTitle) {
        IndustryCase item = requireCaseReadable(id);
        if (!"active".equals(item.getStatus())) {
            throw new BusinessException(400,
                    "任务「" + taskTitle + "」引用的案例已下线，请更换后再发布");
        }
        if (item.getExpireAt() != null && item.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400,
                    "任务「" + taskTitle + "」引用的案例已过期，请更换后再发布");
        }
    }

    /** 校验案例条目可写。 */
    public void assertCaseWritable(UUID id) {
        requireCaseScope(id, false, "案例");
    }

    /**
     * 培训任务发布前校验陪练分身可用：存在、归属本企业、且已发布。
     *
     * <p>保存草稿时允许绑定未发布分身（先配计划、后发分身），发布时再拦。</p>
     */
    public void assertSkillUsable(UUID id, String taskTitle) {
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        assertSkillOwnership(skill);
        if (!"published".equals(skill.getStatus())) {
            throw new BusinessException(400,
                    "任务「" + taskTitle + "」引用的分身未发布，请先发布分身或更换资源");
        }
    }

    /** 校验题集可读：super_admin 全可读；企业管理员可读「本企业 + 平台公共」。 */
    public void assertDatasetReadable(UUID id) {
        assertDatasetScope(id, true, "题集");
    }

    /** 校验题集可写：super_admin 全可写；企业管理员只能写本企业题集（公共模板不可直接改）。 */
    public void assertDatasetWritable(UUID id) {
        assertDatasetScope(id, false, "题集");
    }

    /**
     * 校验评测批次可读：super_admin 全可读；企业管理员只能读本企业批次。
     *
     * <p>批次没有「公共」概念（company_id NOT NULL），因此读与写的范围一致。</p>
     */
    public void assertRunReadable(UUID runId) {
        boolean superAdmin = isUnrestrictedDataScope();
        UUID companyId = TokenContext.getCompanyId();
        if (!superAdmin && companyId == null) {
            throw new BusinessException(403, "无企业归属，无权访问评测批次");
        }
        EvaluationRun run = evaluationRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(404, "评测批次不存在"));
        if (superAdmin) {
            return;
        }
        if (!companyId.equals(run.getCompanyId())) {
            throw new BusinessException(404, "评测批次不存在或不可访问");
        }
    }

    /**
     * 题集归属校验。
     *
     * <p><b>存在性校验对所有角色生效</b>（含 super_admin）：super_admin 只是不受企业范围限制，
     * 并不能把一个不存在的 ID 校验成「通过」。否则调用方会带着幽灵 ID 继续往下走，
     * 最终表现为静默成功（例如生成任务产 0 题却返回 done）。</p>
     */
    private void assertDatasetScope(UUID id, boolean allowPublic, String label) {
        boolean superAdmin = isUnrestrictedDataScope();
        UUID companyId = TokenContext.getCompanyId();
        if (!superAdmin && companyId == null) {
            throw new BusinessException(403, "无企业归属，无权访问" + label);
        }
        EvaluationDataset ds = evaluationDatasetRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, label + "不存在"));
        if (superAdmin) {
            return;
        }
        if (ds.getCompanyId() == null) {
            if (!allowPublic) {
                throw new BusinessException(403, "公共" + label + "仅平台管理员可修改");
            }
            // 公共题集只有已发布版本对其他租户可用；未发布模板仅平台超管可读
            if (!EvaluationDataset.STATUS_PUBLISHED.equals(ds.getStatus())) {
                throw new BusinessException(404, label + "不存在或不可访问");
            }
            return;
        }
        if (!companyId.equals(ds.getCompanyId())) {
            throw new BusinessException(404, label + "不存在或不可访问");
        }
    }

    /** 知识条目归属校验，存在性校验对所有角色生效（见 {@link #assertDatasetScope}）。 */
    private KnowledgeEntry requireKnowledgeScope(UUID id, boolean allowPublic, String label) {
        boolean superAdmin = isUnrestrictedDataScope();
        UUID companyId = TokenContext.getCompanyId();
        if (!superAdmin && companyId == null) {
            throw new BusinessException(403, "无企业归属，无权访问" + label);
        }
        KnowledgeEntry k = knowledgeEntryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, label + "不存在"));
        if (superAdmin) {
            return k;
        }
        if (k.getCompanyId() == null) {
            if (!allowPublic) {
                throw new BusinessException(403, "公共" + label + "仅平台管理员可修改");
            }
            // 公共库：已生效对所有租户可见；未审核候选只允许提交方租户与平台超管按 ID 直读
            boolean published = KnowledgeEntry.STATUS_ACTIVE.equals(k.getStatus());
            boolean submitter = k.getSourceCompanyId() != null
                    && k.getSourceCompanyId().equals(companyId);
            if (!published && !submitter) {
                throw new BusinessException(404, label + "不存在或不可访问");
            }
            return k;
        }
        if (!companyId.equals(k.getCompanyId())) {
            throw new BusinessException(404, label + "不存在或不可访问");
        }
        return k;
    }

    /** 案例条目归属校验，存在性校验对所有角色生效（见 {@link #assertDatasetScope}）。 */
    private IndustryCase requireCaseScope(UUID id, boolean allowPublic, String label) {
        boolean superAdmin = isUnrestrictedDataScope();
        UUID companyId = TokenContext.getCompanyId();
        if (!superAdmin && companyId == null) {
            throw new BusinessException(403, "无企业归属，无权访问" + label);
        }
        IndustryCase c = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, label + "不存在"));
        if (superAdmin) {
            return c;
        }
        if (c.getCompanyId() == null) {
            if (!allowPublic) {
                throw new BusinessException(403, "公共" + label + "仅平台管理员可修改");
            }
            boolean published = IndustryCase.STATUS_ACTIVE.equals(c.getStatus());
            boolean submitter = c.getSourceCompanyId() != null
                    && c.getSourceCompanyId().equals(companyId);
            if (!published && !submitter) {
                throw new BusinessException(404, label + "不存在或不可访问");
            }
            return c;
        }
        if (!companyId.equals(c.getCompanyId())) {
            throw new BusinessException(404, label + "不存在或不可访问");
        }
        return c;
    }
}
