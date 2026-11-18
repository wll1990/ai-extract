package com.aiextract.service.skill;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.AgentSkillDtos;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AgentSkill;
import com.aiextract.model.AgentSkillResource;
import com.aiextract.repository.AgentSkillRepository;
import com.aiextract.repository.AgentSkillResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 技能管理：草稿/发布/下架/版本/预览/开关。
 *
 * <p>权限：平台级读取对所有管理员开放（只读），写入要求 COMPANY_MANAGE；
 * 企业级读写要求同企业。跨租户一律 404。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentSkillService {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final int MAX_RESOURCES = 50;
    private static final int MAX_RESOURCE_CHARS = 200 * 1024;
    private static final long MAX_TOTAL_RESOURCE_CHARS = 2L * 1024 * 1024;
    private static final int MAX_BODY_CHARS = 100_000;

    private final AgentSkillRepository repository;
    private final AgentSkillResourceRepository resourceRepository;
    private final AgentSkillScopeGuard guard;
    private final PromptInjectionScanner scanner;
    private final AgentSkillRegistry registry;
    private final AgentSkillPromptInjector injector;
    private final AgentSkillSettingService settings;
    private final AgentSkillChangeBus changeBus;

    // ==================== 列表 / 详情 ====================

    @Transactional(readOnly = true)
    public List<AgentSkillDtos.ListItem> list(String scope, String keyword, String source) {
        boolean platformScope = "platform".equalsIgnoreCase(scope);
        if (platformScope) {
            guard.requirePlatformScope();
        }
        String kw = keyword == null ? "" : keyword.trim();
        String src = source == null ? "" : source.trim();
        List<AgentSkillRepository.ListRow> rows = new ArrayList<>();
        if (platformScope) {
            rows.addAll(repository.listPlatform(kw, src));
        } else {
            UUID companyId = guard.effectiveCompanyId();
            if (companyId != null) {
                rows.addAll(repository.listTenant(companyId, kw, src));
            }
            rows.addAll(repository.listPlatform(kw, src));
        }
        return project(rows);
    }

    private List<AgentSkillDtos.ListItem> project(List<AgentSkillRepository.ListRow> rows) {
        Map<String, List<AgentSkillRepository.ListRow>> byKey = new LinkedHashMap<>();
        for (AgentSkillRepository.ListRow row : rows) {
            byKey.computeIfAbsent(row.getSkillKey(), k -> new ArrayList<>()).add(row);
        }
        boolean platformAdmin = guard.isPlatformAdmin();
        List<AgentSkillDtos.ListItem> items = new ArrayList<>(byKey.size());
        for (Map.Entry<String, List<AgentSkillRepository.ListRow>> entry : byKey.entrySet()) {
            List<AgentSkillRepository.ListRow> versions = entry.getValue();
            AgentSkillRepository.ListRow live = versions.stream()
                    .filter(r -> AgentSkill.STATUS_PUBLISHED.equals(r.getStatus()))
                    .max(Comparator.comparing(AgentSkillRepository.ListRow::getVersion))
                    .orElse(null);
            AgentSkillRepository.ListRow draft = versions.stream()
                    .filter(r -> AgentSkill.STATUS_DRAFT.equals(r.getStatus()))
                    .max(Comparator.comparing(AgentSkillRepository.ListRow::getVersion))
                    .orElse(null);
            AgentSkillRepository.ListRow latest = versions.stream()
                    .max(Comparator.comparing(AgentSkillRepository.ListRow::getVersion))
                    .orElseThrow();
            String effectiveStatus = live != null ? AgentSkill.STATUS_PUBLISHED
                    : draft != null ? AgentSkill.STATUS_DRAFT : "delisted";
            boolean readOnly = latest.getCompanyId() == null && !platformAdmin;
            items.add(new AgentSkillDtos.ListItem(
                    entry.getKey(), latest.getName(), latest.getSource(), effectiveStatus,
                    live == null ? null : live.getVersion(), live == null ? null : live.getId(),
                    draft == null ? null : draft.getVersion(), draft == null ? null : draft.getId(),
                    latest.getUpdatedAt(), latest.getCompanyId(), readOnly));
        }
        items.sort(Comparator.comparing(AgentSkillDtos.ListItem::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    @Transactional(readOnly = true)
    public AgentSkillDtos.Detail detail(UUID id) {
        AgentSkill skill = requireSkill(id);
        guard.requireReadable(skill);
        boolean readOnly = skill.isPlatformLevel() && !guard.isPlatformAdmin();
        List<AgentSkillDtos.ResourceMeta> resources = resourceRepository.findMeta(skill.getId()).stream()
                .map(r -> new AgentSkillDtos.ResourceMeta(r.getId(), r.getPath(),
                        r.getSize() == null ? 0 : r.getSize()))
                .toList();
        return toDetail(skill, readOnly, resources);
    }

    @Transactional(readOnly = true)
    public AgentSkillDtos.ResourceView resourceView(UUID skillId, UUID resourceId) {
        AgentSkill skill = requireSkill(skillId);
        guard.requireReadable(skill);
        AgentSkillResource resource = resourceRepository.findById(resourceId)
                .filter(r -> r.getSkillId().equals(skillId))
                .orElseThrow(() -> new BusinessException(404, "参考资料不存在"));
        return new AgentSkillDtos.ResourceView(resource.getId(), resource.getPath(), resource.getContent());
    }

    @Transactional(readOnly = true)
    public List<AgentSkillDtos.VersionItem> versions(UUID id) {
        AgentSkill skill = requireSkill(id);
        guard.requireReadable(skill);
        List<AgentSkill> versions = skill.isPlatformLevel()
                ? repository.findByCompanyIdIsNullAndSkillKeyOrderByVersionDesc(skill.getSkillKey())
                : repository.findByCompanyIdAndSkillKeyOrderByVersionDesc(
                        skill.getCompanyId(), skill.getSkillKey());
        return versions.stream()
                .map(v -> new AgentSkillDtos.VersionItem(v.getId(), v.getVersion(), v.getStatus(),
                        v.getPublishedAt(), v.getArchivedAt(), v.getUpdatedAt()))
                .toList();
    }

    // ==================== 创建 / 编辑 ====================

    @Transactional(rollbackFor = Exception.class)
    public AgentSkillDtos.Detail create(AgentSkillDtos.CreateRequest request) {
        boolean platform = "platform".equalsIgnoreCase(request.scope());
        UUID companyId;
        String source;
        if (platform) {
            guard.requirePlatformScope();
            companyId = null;
            source = AgentSkill.SOURCE_PLATFORM;
        } else {
            companyId = guard.effectiveCompanyId();
            if (companyId == null) {
                throw new BusinessException(400, "缺少企业上下文，无法创建企业级技能");
            }
            source = AgentSkill.SOURCE_TENANT;
        }
        validate(request.skillKey(), request.name(), request.description(), request.bodyMd(), request.resources());
        boolean exists = companyId == null
                ? repository.existsByCompanyIdIsNullAndSkillKey(request.skillKey())
                : repository.existsByCompanyIdAndSkillKey(companyId, request.skillKey());
        if (exists) {
            throw BusinessException.conflict("AGENT_SKILL_DUPLICATE_KEY",
                    "该技能 key 已存在，请到已有技能中新建版本");
        }
        Instant now = Instant.now();
        AgentSkill skill = AgentSkill.builder()
                .skillKey(request.skillKey())
                .name(request.name().trim())
                .description(request.description().trim())
                .bodyMd(request.bodyMd())
                .source(source)
                .companyId(companyId)
                .version(1)
                .status(AgentSkill.STATUS_DRAFT)
                .tokenEstimate(estimate(request.bodyMd(), request.resources()))
                .createdBy(TokenContext.get())
                .updatedBy(TokenContext.get())
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.save(skill);
        saveResources(skill.getId(), request.resources());
        return detail(skill.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentSkillDtos.Detail update(UUID id, AgentSkillDtos.UpdateRequest request) {
        AgentSkill skill = requireSkill(id);
        guard.requireWritable(skill);
        if (!skill.isDraft()) {
            throw BusinessException.conflict("AGENT_SKILL_NOT_DRAFT", "已发布/已归档版本不可直接编辑，请新建版本");
        }
        if (request.lockVersion() != null && !request.lockVersion().equals(skill.getLockVersion())) {
            throw BusinessException.conflict("AGENT_SKILL_VERSION_CONFLICT", "内容已被他人修改，请刷新后重试");
        }
        validate(skill.getSkillKey(), request.name(), request.description(), request.bodyMd(), request.resources());
        skill.setName(request.name().trim());
        skill.setDescription(request.description().trim());
        skill.setBodyMd(request.bodyMd());
        skill.setTokenEstimate(estimate(request.bodyMd(), request.resources()));
        skill.setUpdatedBy(TokenContext.get());
        skill.setUpdatedAt(Instant.now());
        repository.save(skill);
        resourceRepository.deleteBySkillId(id);
        saveResources(id, request.resources());
        changeBus.publishAfterCommit(skill.getCompanyId());
        return detail(id);
    }

    // ==================== 版本 / 发布 / 下架 / 删除 ====================

    @Transactional(rollbackFor = Exception.class)
    public AgentSkillDtos.Detail newVersion(UUID sourceId) {
        AgentSkill source = requireSkill(sourceId);
        guard.requireWritable(source);
        if (source.isDraft()) {
            throw BusinessException.conflict("AGENT_SKILL_NOT_VERSIONABLE", "草稿无需新建版本");
        }
        boolean draftExists = source.isPlatformLevel()
                ? repository.findFirstByCompanyIdIsNullAndSkillKeyAndStatusOrderByVersionDesc(
                        source.getSkillKey(), AgentSkill.STATUS_DRAFT).isPresent()
                : repository.findFirstByCompanyIdAndSkillKeyAndStatusOrderByVersionDesc(
                        source.getCompanyId(), source.getSkillKey(), AgentSkill.STATUS_DRAFT).isPresent();
        if (draftExists) {
            throw BusinessException.conflict("AGENT_SKILL_DRAFT_EXISTS", "该技能已有编辑中的草稿");
        }
        int maxVersion = source.isPlatformLevel()
                ? repository.maxPlatformVersion(source.getSkillKey())
                : repository.maxTenantVersion(source.getCompanyId(), source.getSkillKey());
        Instant now = Instant.now();
        AgentSkill draft = AgentSkill.builder()
                .skillKey(source.getSkillKey())
                .name(source.getName())
                .description(source.getDescription())
                .bodyMd(source.getBodyMd())
                .source(source.getSource())
                .companyId(source.getCompanyId())
                .version(maxVersion + 1)
                .status(AgentSkill.STATUS_DRAFT)
                .tokenEstimate(source.getTokenEstimate())
                .createdBy(TokenContext.get())
                .updatedBy(TokenContext.get())
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.save(draft);
        List<AgentSkillResource> resources = resourceRepository.findBySkillIdOrderByPathAsc(sourceId);
        List<AgentSkillResource> copies = resources.stream()
                .map(r -> AgentSkillResource.builder()
                                .skillId(draft.getId())
                        .path(r.getPath())
                        .content(r.getContent())
                        .createdAt(now)
                        .build())
                .toList();
        if (!copies.isEmpty()) {
            resourceRepository.saveAll(copies);
        }
        return detail(draft.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentSkillDtos.Detail publish(UUID id) {
        AgentSkill draft = requireSkill(id);
        guard.requireWritable(draft);
        if (!draft.isDraft()) {
            throw BusinessException.conflict("AGENT_SKILL_NOT_DRAFT", "只有草稿可以发布");
        }
        UUID companyId = draft.getCompanyId();
        String key = draft.getSkillKey();
        Instant now = Instant.now();
        UUID actor = TokenContext.get();
        // 先归档旧 published（批量 update 会清持久化上下文）
        if (companyId == null) {
            repository.archivePlatformPublished(key, now, actor);
        } else {
            repository.archiveTenantPublished(companyId, key, now, actor);
        }
        AgentSkill reloaded = requireSkill(id);
        reloaded.setStatus(AgentSkill.STATUS_PUBLISHED);
        reloaded.setPublishedAt(now);
        reloaded.setPublishedBy(actor);
        reloaded.setUpdatedAt(now);
        reloaded.setUpdatedBy(actor);
        repository.save(reloaded);
        changeBus.publishAfterCommit(companyId);
        return detail(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void archive(UUID id) {
        AgentSkill skill = requireSkill(id);
        guard.requireWritable(skill);
        if (!skill.isPublished()) {
            throw BusinessException.conflict("AGENT_SKILL_NOT_PUBLISHED", "只有已发布技能可以下架");
        }
        skill.setStatus(AgentSkill.STATUS_ARCHIVED);
        skill.setArchivedAt(Instant.now());
        skill.setArchivedBy(TokenContext.get());
        skill.setUpdatedAt(Instant.now());
        repository.save(skill);
        changeBus.publishAfterCommit(skill.getCompanyId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(UUID id) {
        AgentSkill skill = requireSkill(id);
        guard.requireWritable(skill);
        if (!skill.isDraft()) {
            throw BusinessException.conflict("AGENT_SKILL_NOT_DRAFT", "只能删除草稿");
        }
        resourceRepository.deleteBySkillId(id);
        repository.delete(skill);
        changeBus.publishAfterCommit(skill.getCompanyId());
    }

    // ==================== 预览 / 开关 ====================

    @Transactional(readOnly = true)
    public AgentSkillDtos.PreviewResponse preview(AgentSkillDtos.PreviewRequest request) {
        boolean platform = "platform".equalsIgnoreCase(request.scope());
        if (platform) {
            guard.requirePlatformScope();
        }
        UUID companyId = platform ? null : guard.effectiveCompanyId();
        List<AgentSkillRegistry.CatalogEntry> entries = new ArrayList<>(registry.catalog(companyId));
        String key = request.skillKey() == null ? "" : request.skillKey().trim();
        if (!key.isEmpty()) {
            entries.removeIf(e -> e.skillKey().equals(key));
            entries.add(new AgentSkillRegistry.CatalogEntry(key,
                    nullToEmpty(request.name()), nullToEmpty(request.description())));
        }
        String body = request.bodyMd() == null ? "" : request.bodyMd();
        String previewBody = body.length() > SkillBudget.MAX_BODY_CHARS
                ? body.substring(0, SkillBudget.MAX_BODY_CHARS) + "\n[已截断]"
                : body;
        return new AgentSkillDtos.PreviewResponse(
                injector.formatCatalog(entries), previewBody, body.length(), body.length() / 2);
    }

    @Transactional(readOnly = true)
    public AgentSkillDtos.SettingView settings() {
        return new AgentSkillDtos.SettingView(settings.isEnabled(guard.effectiveCompanyId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateSettings(AgentSkillDtos.SettingRequest request) {
        UUID companyId = guard.effectiveCompanyId();
        if (companyId == null) {
            throw new BusinessException(400, "缺少企业上下文");
        }
        if (request.enabled() == null) {
            throw new BusinessException(400, "enabled 不能为空");
        }
        settings.setEnabled(companyId, request.enabled());
    }

    // ==================== 内部 ====================

    private AgentSkill requireSkill(UUID id) {
        return repository.findById(id).orElseThrow(guard::notFound);
    }

    private void saveResources(UUID skillId, List<AgentSkillDtos.ResourceInput> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        List<AgentSkillResource> rows = resources.stream()
                .map(r -> AgentSkillResource.builder()
                                .skillId(skillId)
                        .path(r.path())
                        .content(r.content() == null ? "" : r.content())
                        .createdAt(now)
                        .build())
                .toList();
        resourceRepository.saveAll(rows);
    }

    private void validate(String skillKey, String name, String description, String body,
                          List<AgentSkillDtos.ResourceInput> resources) {
        if (skillKey == null || !KEY_PATTERN.matcher(skillKey).matches()) {
            throw new BusinessException(400, "key 只能是小写字母/数字/连字符，长度 1-64");
        }
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            throw new BusinessException(400, "名称和描述不能为空");
        }
        if (body == null || body.isBlank()) {
            throw new BusinessException(400, "技能正文不能为空");
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw new BusinessException(400, "正文不能超过 " + MAX_BODY_CHARS + " 字符");
        }
        scanner.assertClean("名称", name);
        scanner.assertClean("描述", description);
        scanner.assertClean("正文", body);
        if (resources == null) {
            return;
        }
        if (resources.size() > MAX_RESOURCES) {
            throw new BusinessException(400, "参考资料最多 " + MAX_RESOURCES + " 个");
        }
        long total = 0;
        for (AgentSkillDtos.ResourceInput resource : resources) {
            if (!AgentSkillTools.safePath(resource.path())) {
                throw new BusinessException(400, "参考资料路径必须是 references/ 下的相对路径");
            }
            String content = resource.content() == null ? "" : resource.content();
            if (content.length() > MAX_RESOURCE_CHARS) {
                throw new BusinessException(400, "单个参考资料不能超过 200KB");
            }
            total += content.length();
            scanner.assertClean("参考资料 " + resource.path(), content);
        }
        if (total > MAX_TOTAL_RESOURCE_CHARS) {
            throw new BusinessException(400, "参考资料总大小不能超过 2MB");
        }
    }

    private static int estimate(String body, List<AgentSkillDtos.ResourceInput> resources) {
        int chars = body == null ? 0 : body.length();
        if (resources != null) {
            for (AgentSkillDtos.ResourceInput r : resources) {
                chars += r.content() == null ? 0 : r.content().length();
            }
        }
        return chars / 2;
    }

    private AgentSkillDtos.Detail toDetail(AgentSkill skill, boolean readOnly,
                                           List<AgentSkillDtos.ResourceMeta> resources) {
        return new AgentSkillDtos.Detail(skill.getId(), skill.getSkillKey(), skill.getName(),
                skill.getDescription(), skill.getBodyMd(), skill.getSource(), skill.getCompanyId(),
                skill.getVersion(), skill.getStatus(),
                skill.getLockVersion() == null ? 0 : skill.getLockVersion(),
                readOnly, resources, skill.getUpdatedAt());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
