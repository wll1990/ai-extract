package com.aiextract.service;

import com.aiextract.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浏览器 WebSocket → DashScope Paraformer 中继。
 *
 * <p>认证：URL 查询参数 ?token=xxx（JWT）</p>
 * <p>上行：浏览器发送二进制 PCM 音频帧 → 转发到 DashScope</p>
 * <p>下行：DashScope 返回识别文本 → 以 JSON 转发给浏览器</p>
 *
 * <p>每条下行消息格式：</p>
 * <pre>{@code {"text": "识别到的文字", "isFinal": true/false}}</pre>
 *
 * @author AI Extract Team
 * @since 2026-07-30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SttWebSocketHandler extends TextWebSocketHandler {

    private final SttService sttService;
    private final JwtUtil jwtUtil;

    /** sessionId → SttSession */
    private final ConcurrentHashMap<String, SttService.SttSession> sessions = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 验证 JWT token
        String token = extractToken(session);
        if (token == null || token.isBlank()) {
            log.warn("WebSocket 连接缺少 token, session={}", session.getId());
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        String userId;
        try {
            userId = jwtUtil.getUserIdFromToken(token).toString();
        } catch (Exception e) {
            log.warn("WebSocket token 验证失败, session={}", session.getId());
            closeSession(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        log.info("STT WebSocket 连接已建立, session={}, userId={}", session.getId(), userId);

        // 创建 DashScope 会话
        try {
            SttService.SttSession sttSession = sttService.createSession(new SttService.Listener() {
                @Override
                public void onTranscription(String text, boolean isFinal) {
                    sendToBrowser(session, text, isFinal);
                }

                @Override
                public void onError(String message) {
                    sendErrorToBrowser(session, message);
                }

                @Override
                public void onClosed() {
                    // DashScope 侧关闭，清理浏览器连接
                    sessions.remove(session.getId());
                    closeSession(session, CloseStatus.SERVER_ERROR);
                }
            });
            sessions.put(session.getId(), sttSession);
        } catch (Exception e) {
            log.error("创建 STT 会话失败, session={}: {}", session.getId(), e.getMessage());
            sendErrorToBrowser(session, e.getMessage());
            closeSession(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SttService.SttSession sttSession = sessions.get(session.getId());
        if (sttSession != null) {
            ByteBuffer buf = message.getPayload();
            byte[] payload = new byte[buf.remaining()];
            buf.get(payload);
            sttSession.sendAudio(payload);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if ("finish".equals(payload)) {
            // 浏览器主动结束识别
            SttService.SttSession sttSession = sessions.remove(session.getId());
            if (sttSession != null) {
                sttSession.finish();
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("STT WebSocket 连接关闭, session={}, status={}", session.getId(), status);
        SttService.SttSession sttSession = sessions.remove(session.getId());
        if (sttSession != null) {
            sttSession.close();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("STT WebSocket 传输异常, session={}: {}", session.getId(), exception.getMessage());
        SttService.SttSession sttSession = sessions.remove(session.getId());
        if (sttSession != null) {
            sttSession.close();
        }
        closeSession(session, CloseStatus.SERVER_ERROR);
    }

    // ---- private helpers ----

    private String extractToken(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        String query = uri.getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if ("token".equals(kv[0]) && kv.length > 1) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void sendToBrowser(WebSocketSession session, String text, boolean isFinal) {
        if (!session.isOpen()) return;
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("text", text);
            msg.put("isFinal", isFinal);
            session.sendMessage(new TextMessage(MAPPER.writeValueAsString(msg)));
        } catch (IOException e) {
            log.warn("发送识别结果到浏览器失败: {}", e.getMessage());
        }
    }

    private void sendErrorToBrowser(WebSocketSession session, String error) {
        if (!session.isOpen()) return;
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("error", error);
            session.sendMessage(new TextMessage(MAPPER.writeValueAsString(msg)));
        } catch (IOException e) {
            log.warn("发送错误消息到浏览器失败: {}", e.getMessage());
        }
    }

    private void closeSession(WebSocketSession session, CloseStatus status) {
        if (session.isOpen()) {
            try {
                session.close(status);
            } catch (IOException ignored) {
            }
        }
    }

}
