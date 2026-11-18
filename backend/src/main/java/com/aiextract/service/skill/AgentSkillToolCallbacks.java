package com.aiextract.service.skill;

import com.aiextract.service.tool.SimpleToolCallback;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/**
 * 技能工具集。**不注册到 defaultToolCallbacks**，只由 ChatStreamAdapter 在
 * {@code agentSkills=true} 的请求上按需注册，避免评测/访谈等链路被广告工具 schema。
 */
@Component
@RequiredArgsConstructor
public class AgentSkillToolCallbacks {

    private static final String LIST_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    private static final String USE_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"skillKey\":{\"type\":\"string\",\"description\":\"技能稳定标识，来自可用技能目录\"},"
                    + "\"offset\":{\"type\":\"integer\",\"minimum\":0,\"description\":\"正文起始字符偏移，默认 0\"}"
                    + "},\"required\":[\"skillKey\"],\"additionalProperties\":false}";
    private static final String REF_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"skillKey\":{\"type\":\"string\"},"
                    + "\"path\":{\"type\":\"string\",\"description\":\"references/ 下的相对路径\"},"
                    + "\"offset\":{\"type\":\"integer\",\"minimum\":0}"
                    + "},\"required\":[\"skillKey\",\"path\"],\"additionalProperties\":false}";

    private final AgentSkillTools tools;

    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{
                new SimpleToolCallback(
                        definition("list_skills",
                                "列出当前企业可用的模型技能（key、名称、说明）。技能不是分身。",
                                LIST_SCHEMA),
                        (String input, ToolContext ctx) -> tools.listSkills(ctx)),
                new SimpleToolCallback(
                        definition("use_skill",
                                "按 skillKey 读取技能正文（方法论/步骤/话术）。正文较长时可传 offset 续读。",
                                USE_SCHEMA),
                        tools::useSkill),
                new SimpleToolCallback(
                        definition("read_skill_reference",
                                "读取技能 references/ 下的参考资料片段，可传 offset 续读。",
                                REF_SCHEMA),
                        tools::readReference)
        };
    }

    private static ToolDefinition definition(String name, String description, String schema) {
        return DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
    }
}
