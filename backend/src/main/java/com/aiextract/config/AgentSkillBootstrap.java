package com.aiextract.config;

import com.aiextract.model.AgentSkill;
import com.aiextract.repository.AgentSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * 首次启动把 classpath:skills/*&#47;SKILL.md 初始化为平台内置技能。
 *
 * <p>只插入、不覆盖管理端修改；多实例并发由唯一索引兜底（每个 save 独立事务，
 * 冲突仅跳过该条）。内置内容视为可信，不做注入扫描。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSkillBootstrap implements ApplicationRunner {

    private final AgentSkillRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:skills/*/SKILL.md");
            for (Resource resource : resources) {
                seed(resource);
            }
        } catch (Exception e) {
            log.warn("内置技能初始化失败: {}", e.toString());
        }
    }

    private void seed(Resource resource) throws Exception {
        String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String key = resource.getURL().getPath();
        int marker = key.indexOf("/skills/");
        if (marker >= 0) {
            key = key.substring(marker + "/skills/".length());
        }
        int slash = key.indexOf('/');
        if (slash > 0) {
            key = key.substring(0, slash);
        }
        Frontmatter frontmatter = parse(text);
        if (frontmatter.name == null || frontmatter.description == null) {
            log.warn("内置技能缺少 frontmatter，跳过: {}", key);
            return;
        }
        if (repository.existsByCompanyIdIsNullAndSkillKey(key)) {
            return;
        }
        Instant now = Instant.now();
        AgentSkill skill = AgentSkill.builder()
                .skillKey(key)
                .name(frontmatter.name)
                .description(frontmatter.description)
                .bodyMd(frontmatter.body)
                .source(AgentSkill.SOURCE_BUILTIN)
                .companyId(null)
                .version(1)
                .status(AgentSkill.STATUS_PUBLISHED)
                .tokenEstimate(frontmatter.body.length() / 2)
                .createdAt(now)
                .updatedAt(now)
                .publishedAt(now)
                .build();
        try {
            repository.save(skill);
            log.info("内置技能已初始化: {}", key);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.debug("内置技能已存在（并发初始化）: {}", key);
        }
    }

    private static Frontmatter parse(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 3 || !"---".equals(lines[0].trim())) {
            return new Frontmatter(null, null, text);
        }
        String name = null;
        String description = null;
        int index = 1;
        for (; index < lines.length; index++) {
            String line = lines[index].trim();
            if ("---".equals(line)) {
                index++;
                break;
            }
            if (line.startsWith("name:")) {
                name = line.substring(5).trim();
            } else if (line.startsWith("description:")) {
                description = line.substring(12).trim();
            }
        }
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, index, lines.length)).trim();
        return new Frontmatter(name, description, body);
    }

    private record Frontmatter(String name, String description, String body) {
    }
}
