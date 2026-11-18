package com.aiextract.service.skill;

import com.aiextract.model.AgentSkill;
import com.aiextract.repository.AgentSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已发布技能目录（按企业缓存 5 分钟）。正文不缓存，发布即生效。
 *
 * <p>只依赖 Repository，不读 SecurityContext；失效由 Service 在事务提交后调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSkillRegistry {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_COMPANIES = 500;
    private static final String PLATFORM_KEY = "PLATFORM";

    public record CatalogEntry(String skillKey, String name, String description) {
    }

    private record Cached(long expiresAt, List<CatalogEntry> entries) {
    }

    private final AgentSkillRepository repository;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /** 企业可见目录：企业级覆盖平台级同名 key。 */
    public List<CatalogEntry> catalog(UUID companyId) {
        String cacheKey = companyId == null ? PLATFORM_KEY : companyId.toString();
        long now = System.currentTimeMillis();
        Cached cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt() > now) {
            return cached.entries();
        }
        evictIfNeeded(now);
        List<CatalogEntry> entries = load(companyId);
        cache.put(cacheKey, new Cached(now + TTL.toMillis(), entries));
        return entries;
    }

    /** 解析具体技能：企业级优先，其次平台级。 */
    public Optional<AgentSkill> resolve(UUID companyId, String skillKey) {
        if (skillKey == null || skillKey.isBlank()) {
            return Optional.empty();
        }
        if (companyId != null) {
            Optional<AgentSkill> tenant = repository
                    .findFirstByCompanyIdAndSkillKeyAndStatusOrderByVersionDesc(
                            companyId, skillKey, AgentSkill.STATUS_PUBLISHED);
            if (tenant.isPresent()) {
                return tenant;
            }
        }
        return repository.findFirstByCompanyIdIsNullAndSkillKeyAndStatusOrderByVersionDesc(
                skillKey, AgentSkill.STATUS_PUBLISHED);
    }

    public void invalidate(UUID companyId) {
        if (companyId == null) {
            cache.clear();
        } else {
            cache.remove(companyId.toString());
        }
    }

    public void invalidateAll() {
        cache.clear();
    }

    private List<CatalogEntry> load(UUID companyId) {
        Map<String, CatalogEntry> merged = new LinkedHashMap<>();
        for (AgentSkillRepository.CatalogRow row : repository.catalogPlatform()) {
            merged.put(row.getSkillKey(), new CatalogEntry(row.getSkillKey(), row.getName(), row.getDescription()));
        }
        if (companyId != null) {
            for (AgentSkillRepository.CatalogRow row : repository.catalogTenant(companyId)) {
                merged.put(row.getSkillKey(), new CatalogEntry(row.getSkillKey(), row.getName(), row.getDescription()));
            }
        }
        return new ArrayList<>(merged.values());
    }

    private void evictIfNeeded(long now) {
        cache.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        if (cache.size() <= MAX_COMPANIES) {
            return;
        }
        cache.entrySet().stream()
                .sorted((a, b) -> Long.compare(a.getValue().expiresAt(), b.getValue().expiresAt()))
                .limit(cache.size() - MAX_COMPANIES)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(cache::remove);
    }
}
