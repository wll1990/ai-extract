package com.aiextract.config;

import com.aiextract.service.tool.PlatformTools;
import com.aiextract.service.tool.SimpleToolCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式注册平台只读工具。
 *
 * <p>Spring AI 2.0 不自动扫描 @Tool bean；这里手写 schema 构造 ToolCallback，
 * 不依赖反射式 schema 生成器，避免工具模块的 Jackson 版本冲突。</p>
 */
@Configuration
@RequiredArgsConstructor
public class ToolConfig {

    private static final String EMPTY_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    private static final String SKILL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"skillId\":{\"type\":\"string\","
                    + "\"description\":\"分身 UUID\"}},\"required\":[\"skillId\"],"
                    + "\"additionalProperties\":false}";
    private static final String KEYWORD_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\","
                    + "\"description\":\"分身名称关键词\"}},\"required\":[\"keyword\"],"
                    + "\"additionalProperties\":false}";

    private final ObjectMapper objectMapper;

    @Bean
    public ToolCallbackProvider platformToolCallbackProvider(PlatformTools tools) {
        return ToolCallbackProvider.from(
                new SimpleToolCallback(
                        definition("listMySkills", "列出当前登录用户可用的分身（ID、名称、类型、状态）", EMPTY_SCHEMA),
                        input -> tools.listMySkills()),
                new SimpleToolCallback(
                        definition("getSkillProfile", "按分身 ID 获取简介（名称、类型、状态）", SKILL_SCHEMA),
                        input -> tools.getSkillProfile(read(input, "skillId"))),
                new SimpleToolCallback(
                        definition("searchMySkills", "按关键词搜索当前用户的分身名称", KEYWORD_SCHEMA),
                        input -> tools.searchMySkills(read(input, "keyword")))
        );
    }

    private static ToolDefinition definition(String name, String description, String schema) {
        return DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
    }

    private String read(String input, String field) {
        try {
            return objectMapper.readTree(input == null ? "{}" : input).path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
