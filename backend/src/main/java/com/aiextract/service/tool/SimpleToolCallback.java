package com.aiextract.service.tool;

import com.aiextract.config.TokenContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 极简 ToolCallback：手写 JSON Schema + 显式 handler。
 *
 * <p>两种 handler：单参用于只需要入参的工具（自动还原租户上下文）；
 * 双参用于需要读取 {@link ToolContext} 的工具（技能预算、模式开关等）。</p>
 */
public final class SimpleToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final Function<String, String> handler;
    private final BiFunction<String, ToolContext, String> contextHandler;

    public SimpleToolCallback(ToolDefinition definition, Function<String, String> handler) {
        this.definition = definition;
        this.handler = handler;
        this.contextHandler = null;
    }

    public SimpleToolCallback(ToolDefinition definition,
                              BiFunction<String, ToolContext, String> contextHandler) {
        this.definition = definition;
        this.handler = null;
        this.contextHandler = contextHandler;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        if (handler != null) {
            return handler.apply(toolInput);
        }
        return contextHandler.apply(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        if (contextHandler != null) {
            return contextHandler.apply(toolInput, toolContext);
        }
        Map<String, Object> context = toolContext == null ? Map.of() : toolContext.getContext();
        UUID userId = uuid(context.get("userId"));
        if (userId == null) {
            return call(toolInput);
        }
        return TokenContext.callWith(userId, uuid(context.get("companyId")),
                () -> call(toolInput));
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
