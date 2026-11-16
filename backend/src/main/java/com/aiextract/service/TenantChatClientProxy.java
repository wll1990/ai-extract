package com.aiextract.service;

import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * 租户 ChatClient 代理：每次调用按当前企业解析真实 Client。
 *
 * <p>不替换任何调用方：{@code ChatStreamAdapter} 等注入的是 {@link ChatClient}，
 * Spring 在有 {@code @Primary} 时注入本代理。</p>
 */
public final class TenantChatClientProxy {

    private TenantChatClientProxy() {
    }

    public static ChatClient of(ChatClientResolver resolver) {
        return (ChatClient) Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class[]{ChatClient.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "TenantChatClientProxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    try {
                        return method.invoke(resolver.current(), args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
