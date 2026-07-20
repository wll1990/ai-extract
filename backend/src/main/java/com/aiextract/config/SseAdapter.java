package com.aiextract.config;

import com.aiextract.model.ChatChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;

/**
 * SSE 适配器 — Flux → SseEmitter 的复用工具
 *
 * <p>所有 Controller 通过此工具将统一的 Flux&lt;ChatChunk&gt; 包装为 SseEmitter，
 * 不再手动操作 HttpServletResponse / PrintWriter。</p>
 *
 * <p>新增客户端（小程序、App）只需新增 Controller，复用同一适配器。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-14
 */
@Slf4j
public final class SseAdapter {

    private SseAdapter() { /* 工具类，禁止实例化 */ }

    /** 默认超时（2 分钟），AI 长回答场景足够 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    /**
     * 将 Flux&lt;ChatChunk&gt; 包装为 SseEmitter
     *
     * <p>输出格式与现有前端 sse.ts 兼容：
     * {@code event:message\ndata:{"type":"content","content":"..."}\n\n}</p>
     *
     * <p>连接建立后立即发送 heartbeat 事件，确认 SSE 链路通畅。</p>
     *
     * @param flux    ChatStreamService 返回的流
     * @param timeout 超时时长，超时后 emitter 自动关闭
     * @return 已订阅的 SseEmitter，可直接从 Controller 返回
     */
    public static SseEmitter fromFlux(Flux<ChatChunk> flux, Duration timeout) {
        SseEmitter emitter = new SseEmitter(timeout != null ? timeout.toMillis() : DEFAULT_TIMEOUT.toMillis());

        // 连接确认：立即发送 heartbeat，验证 SSE 链路
        // 前端 sse.ts 忽略未知 type，不会造成渲染问题
        heartbeat(emitter, "connected");

        flux.doOnSubscribe(s -> log.debug("SSE 流已订阅"))
            .subscribe(
                chunk -> {
                    try {
                        emitter.send(SseEmitter.event()
                            .name("message")
                            .data(chunk.toSseJson()));
                    } catch (IOException e) {
                        log.warn("SSE 发送失败，客户端可能已断开: {}", e.getMessage());
                    }
                },
                error -> {
                    log.error("SSE 流异常: {}", error.getMessage(), error);
                    try {
                        emitter.send(SseEmitter.event()
                            .name("message")
                            .data(ChatChunk.error("AI服务异常，请稍后重试").toSseJson()));
                    } catch (IOException ignored) { /* 客户端可能已断开 */ }
                    emitter.complete();
                },
                () -> {
                    heartbeat(emitter, "done");
                    emitter.complete();
                }
            );

        emitter.onCompletion(() -> log.debug("SSE 流正常结束"));
        emitter.onTimeout(() -> {
            log.warn("SSE 流超时");
            heartbeat(emitter, "timeout");
            emitter.complete();
        });
        emitter.onError(err -> log.warn("SSE 连接异常: {}", err.getMessage()));

        return emitter;
    }

    /** 使用默认超时（2 分钟） */
    public static SseEmitter fromFlux(Flux<ChatChunk> flux) {
        return fromFlux(flux, DEFAULT_TIMEOUT);
    }

    private static void heartbeat(SseEmitter emitter, String status) {
        try {
            emitter.send(SseEmitter.event()
                .name("message")
                .data("{\"type\":\"heartbeat\",\"status\":\"" + status + "\"}"));
        } catch (IOException e) {
            log.debug("Heartbeat 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
