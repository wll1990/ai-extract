package com.aiextract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * DashScope Paraformer 实时语音识别服务。
 *
 * <p>每个浏览器 WebSocket 会话对应一个 {@link SttSession}，
 * 内部维护一条到 DashScope 的 WebSocket 连接。</p>
 *
 * <p>协议：Paraformer Realtime v2</p>
 * <ul>
 *   <li>1. 连接 wss://dashscope.aliyuncs.com/api-ws/v1/realtime</li>
 *   <li>2. 发送 run-task（PCM 16kHz 16bit 单声道）</li>
 *   <li>3. 持续发送音频二进制帧</li>
 *   <li>4. 接收 JSON 文本帧：{@code {"payload": {"result": "...", "is_final": true/false}}}</li>
 *   <li>5. 发送 finish-task 结束识别</li>
 * </ul>
 *
 * @author AI Extract Team
 * @since 2026-07-30
 */
@Slf4j
@Service
public class SttService {

    private static final String DASHSCOPE_STT_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 复用 HttpClient 实例，避免高并发耗尽连接 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${ai.dashscope.api-key}")
    private String apiKey;

    @Value("${ai.dashscope.stt.model:paraformer-realtime-v2}")
    private String model;

    @Value("${ai.dashscope.stt.sample-rate:16000}")
    private int sampleRate;

    @Value("${ai.dashscope.stt.format:pcm}")
    private String format;

    /**
     * 创建一个 STT 会话 — 同步完成 DashScope WebSocket 握手和 run-task 协议协商。
     *
     * @param listener 识别结果回调
     * @return 可用的 SttSession
     * @throws RuntimeException 连接或握手失败
     */
    public SttSession createSession(Listener listener) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        String url = DASHSCOPE_STT_URL + "?model=" + model;

        DashScopeWebSocketListener dashScopeListener = new DashScopeWebSocketListener(taskId, listener);

        WebSocket ws;
        try {
            ws = HTTP_CLIENT.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .buildAsync(URI.create(url), dashScopeListener)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("DashScope WebSocket 连接失败: {}", e.getMessage());
            throw new RuntimeException("语音识别服务连接失败", e);
        }

        // 发送 run-task 启动识别
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_group", "audio");
        payload.put("task", "asr");
        payload.put("format", format);
        payload.put("sample_rate", sampleRate);

        Map<String, Object> startMsg = new LinkedHashMap<>();
        startMsg.put("header", header);
        startMsg.put("payload", payload);

        try {
            String json = MAPPER.writeValueAsString(startMsg);
            ws.sendText(json, true);
            log.info("DashScope STT 识别已启动, task_id={}", taskId);
        } catch (Exception e) {
            log.error("发送 run-task 失败: {}", e.getMessage());
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "run-task failed");
            throw new RuntimeException("启动语音识别失败", e);
        }

        return new SttSessionImpl(ws, taskId);
    }

    /**
     * STT 识别结果回调
     */
    public interface Listener {
        void onTranscription(String text, boolean isFinal);

        void onError(String message);

        void onClosed();
    }

    /**
     * STT 会话 — 发送音频 / 结束识别
     */
    public interface SttSession {
        void sendAudio(byte[] pcmData);

        void finish();

        void close();
    }

    private static class SttSessionImpl implements SttSession {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SttSessionImpl.class);
        private final WebSocket ws;
        private final String taskId;
        private volatile boolean finished;

        SttSessionImpl(WebSocket ws, String taskId) {
            this.ws = ws;
            this.taskId = taskId;
        }

        @Override
        public void sendAudio(byte[] pcmData) {
            if (finished) return;
            try {
                ws.sendBinary(ByteBuffer.wrap(pcmData), false);
            } catch (Exception e) {
                log.warn("发送音频数据失败: {}", e.getMessage());
            }
        }

        @Override
        public void finish() {
            if (finished) return;
            finished = true;
            try {
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("action", "finish-task");
                header.put("task_id", taskId);
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("header", header);
                ws.sendText(MAPPER.writeValueAsString(msg), true);
            } catch (Exception e) {
                log.warn("发送 finish-task 失败: {}", e.getMessage());
            }
        }

        @Override
        public void close() {
            finish();
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * DashScope WebSocket 监听器 — 处理 Paraformer 协议帧
     */
    private static class DashScopeWebSocketListener implements WebSocket.Listener {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DashScopeWebSocketListener.class);

        private final String taskId;
        private final Listener listener;
        private final StringBuilder textBuffer = new StringBuilder();

        DashScopeWebSocketListener(String taskId, Listener listener) {
            this.taskId = taskId;
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String text = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> msg = MAPPER.readValue(text, Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) msg.get("payload");
                    if (payload != null) {
                        String result = (String) payload.get("result");
                        if (result != null && !result.isEmpty()) {
                            // DashScope 返回的 is_final 可能是 Boolean 或 String
                            Object isFinalObj = payload.get("is_final");
                            boolean isFinal = isFinalObj instanceof Boolean b ? b
                                    : "true".equalsIgnoreCase(String.valueOf(isFinalObj));
                            listener.onTranscription(result, isFinal);
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析 DashScope 识别结果失败: {} raw={}", e.getMessage(),
                            text.length() > 200 ? text.substring(0, 200) + "..." : text);
                }
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("DashScope WebSocket 关闭, task_id={}, code={}, reason={}", taskId, statusCode, reason);
            listener.onClosed();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("DashScope WebSocket 异常, task_id={}: {}", taskId, error.getMessage());
            listener.onError(error.getMessage());
        }
    }
}
