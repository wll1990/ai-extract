package com.aiextract.service.skill;

import com.aiextract.model.AgentSkill;
import com.aiextract.repository.AgentSkillResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 三个技能工具的实现。所有上下文都来自 {@link ToolContext}，不读 ThreadLocal。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSkillTools {

    private final AgentSkillRegistry registry;
    private final AgentSkillResourceRepository resources;
    private final ObjectMapper objectMapper;

    public String listSkills(ToolContext context) {
        if (!enabled(context)) {
            return "技能不可用。";
        }
        SkillBudget budget = budget(context);
        if (budget == null) {
            return "技能不可用。";
        }
        List<AgentSkillRegistry.CatalogEntry> entries = registry.catalog(companyId(context));
        if (entries.isEmpty()) {
            return "当前没有可用技能。";
        }
        String text = entries.stream()
                .map(e -> e.skillKey() + " | " + e.name() + " | " + e.description())
                .collect(Collectors.joining("\n"));
        String rejected = budget.consumeList(text.length());
        return rejected != null ? rejected : text;
    }

    public String useSkill(String input, ToolContext context) {
        if (!enabled(context)) {
            return "技能不可用。";
        }
        SkillBudget budget = budget(context);
        if (budget == null) {
            return "技能不可用。";
        }
        JsonNode args = parse(input);
        String skillKey = args.path("skillKey").asText("");
        int offset = Math.max(0, args.path("offset").asInt(0));
        Optional<AgentSkill> found = registry.resolve(companyId(context), skillKey);
        if (found.isEmpty()) {
            return "技能不存在或不可用。";
        }
        AgentSkill skill = found.get();
        log.info("skill_used userId={} companyId={} skillKey={} version={} conversationId={}",
                context.getContext().get("userId"), context.getContext().get("companyId"),
                skill.getSkillKey(), skill.getVersion(), context.getContext().get("conversationId"));
        String body = skill.getBodyMd() == null ? "" : skill.getBodyMd();
        int end = Math.min(body.length(), offset + SkillBudget.MAX_BODY_CHARS);
        String chunk = offset >= body.length() ? "" : body.substring(offset, end);
        StringBuilder out = new StringBuilder();
        out.append("技能：").append(skill.getName()).append("\n\n").append(chunk);
        if (end < body.length()) {
            out.append("\n\n[正文未读完，继续调用 use_skill 并传 offset=").append(end).append("]");
        }
        List<String> refs = resources.findMeta(skill.getId()).stream()
                .map(AgentSkillResourceRepository.MetaRow::getPath)
                .toList();
        if (!refs.isEmpty()) {
            out.append("\n\n参考资料：").append(String.join(", ", refs));
        }
        String text = out.toString();
        String rejected = budget.consumeUse(text.length());
        return rejected != null ? rejected : text;
    }

    public String readReference(String input, ToolContext context) {
        if (!enabled(context)) {
            return "技能不可用。";
        }
        SkillBudget budget = budget(context);
        if (budget == null) {
            return "技能不可用。";
        }
        JsonNode args = parse(input);
        String skillKey = args.path("skillKey").asText("");
        String path = args.path("path").asText("");
        int offset = Math.max(0, args.path("offset").asInt(0));
        if (!safePath(path)) {
            return "参考资料路径不合法。";
        }
        Optional<AgentSkill> found = registry.resolve(companyId(context), skillKey);
        if (found.isEmpty()) {
            return "技能不存在或不可用。";
        }
        Optional<com.aiextract.model.AgentSkillResource> resource =
                resources.findBySkillIdAndPath(found.get().getId(), path);
        if (resource.isEmpty()) {
            return "参考资料不存在。";
        }
        String content = resource.get().getContent() == null ? "" : resource.get().getContent();
        int end = Math.min(content.length(), offset + SkillBudget.MAX_REF_CHARS);
        String chunk = offset >= content.length() ? "" : content.substring(offset, end);
        String text = chunk + (end < content.length()
                ? "\n\n[未读完，继续调用 read_skill_reference 并传 offset=" + end + "]"
                : "");
        String rejected = budget.consumeRef(text.length());
        return rejected != null ? rejected : text;
    }

    private static boolean enabled(ToolContext context) {
        return context != null && Boolean.TRUE.equals(context.getContext().get("agentSkills"));
    }

    private static UUID companyId(ToolContext context) {
        Object value = context.getContext().get("companyId");
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static SkillBudget budget(ToolContext context) {
        Object value = context.getContext().get("skillBudget");
        return value instanceof SkillBudget skillBudget ? skillBudget : null;
    }

    private JsonNode parse(String input) {
        try {
            return objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** 只允许 references/ 下的相对路径。 */
    static boolean safePath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("references/")) {
            return false;
        }
        String lower = path.toLowerCase();
        return !lower.contains("..") && !lower.contains("\\") && !lower.contains("%2e")
                && !lower.contains("%2f") && !lower.contains("%5c") && !lower.contains("\u0000")
                && !lower.contains("//");
    }
}
