package com.aiextract.service.tool;

import com.aiextract.config.TokenContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 极简 ToolCallback：手写 JSON Schema + 显式 handler。
 *
 * <p>不使用反射式 schema 生成，避免工具模块把 victools/Jackson 版本拖进运行时。</p>
 *
 * <p>工具可能在任何模型回调线程执行，那里没有请求 ThreadLocal；
 * 因此优先从 {@link ToolContext} 还原租户上下文，缺失时才回退当前线程。</p>
 */
public final class SimpleToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final Function<String, String> handler;

    public SimpleToolCallback(ToolDefinition definition, Function<String, String> handler) {
        this.definition = definition;
        this.handler = handler;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return handler.apply(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
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
