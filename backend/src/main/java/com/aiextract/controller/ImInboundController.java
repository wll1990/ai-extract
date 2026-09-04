package com.aiextract.controller;

import com.aiextract.config.ImBridgeAuth;
import com.aiextract.config.SseAdapter;
import com.aiextract.dto.ImActiveBotResponse;
import com.aiextract.dto.ImCompleteRequest;
import com.aiextract.dto.ImMessageRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.service.ImInboundService;
import com.aiextract.service.ImLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * IM 入站内部接口 — 供各平台传输层调用（HMAC 鉴权，不进 JWT 网关）。
 *
 * <p>路由契约平台无关（{@code /internal/im/**}），新增钉钉/企微无需改此控制器。
 * 飞书桥通过 {@link ImBridgeAuth} 相同的 HMAC 算法签名调用。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@Slf4j
@RestController
@RequestMapping("/internal/im")
@RequiredArgsConstructor
public class ImInboundController {

    /** 时间戳允许偏差（秒），防重放 */
    private static final long MAX_TIMESTAMP_SKEW_SECONDS = 300;
    /** nonce 一次性校验 key 前缀 / TTL（与时间戳窗口一致） */
    private static final String NONCE_PREFIX = "im:nonce:";
    private static final Duration NONCE_TTL = Duration.ofMinutes(5);

    private final ImInboundService imInboundService;
    private final ImLifecycleService imLifecycleService;
    private final ImBridgeAuth imBridgeAuth;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 入站消息 → SSE 流式回复（复用 ChatChunk 协议 + SseAdapter）。
     */
    @PostMapping(value = "/message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter message(
            @RequestHeader(value = "X-Im-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Im-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Im-Signature", required = false) String signature,
            @RequestBody String body) {
        verify(timestamp, nonce, body, signature);
        ImMessageRequest req = parse(body, ImMessageRequest.class);
        return SseAdapter.fromFlux(imInboundService.receive(req));
    }

    /**
     * 扫码完成回调（仅飞书桥调用）。
     */
    @PostMapping("/{channelType}/complete")
    public Map<String, Object> complete(
            @PathVariable String channelType,
            @RequestHeader(value = "X-Im-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Im-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Im-Signature", required = false) String signature,
            @RequestBody String body) {
        verify(timestamp, nonce, body, signature);
        ImCompleteRequest req = parse(body, ImCompleteRequest.class);
        imLifecycleService.completePublish(channelType, req);
        return Map.of("ok", true);
    }

    /**
     * 传输层开机对账：返回需连接的 bot 列表（含解密后 appSecret）。
     */
    @GetMapping("/{channelType}/active")
    public List<ImActiveBotResponse> active(
            @PathVariable String channelType,
            @RequestHeader(value = "X-Im-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Im-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Im-Signature", required = false) String signature) {
        verify(timestamp, nonce, "", signature);
        return imLifecycleService.listActiveBots(channelType);
    }

    private void verify(String timestamp, String nonce, String body, String signature) {
        if (!imBridgeAuth.verify(timestamp, nonce, body, signature)) {
            throw new BusinessException(401, "签名校验失败");
        }
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() / 1000 - ts) > MAX_TIMESTAMP_SKEW_SECONDS) {
                throw new BusinessException(401, "请求已过期");
            }
        } catch (NumberFormatException e) {
            throw new BusinessException(401, "非法时间戳");
        }
        // nonce 一次性校验（防时间窗内重放）；Redis 异常 fail-open 放行
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(NONCE_PREFIX + nonce, "1", NONCE_TTL);
            if (Boolean.FALSE.equals(first)) {
                throw new BusinessException(401, "重复请求");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("IM nonce 校验 Redis 异常，fail-open 放行: {}", e.getMessage());
        }
    }

    private <T> T parse(String body, Class<T> clazz) {
        try {
            return objectMapper.readValue(body, clazz);
        } catch (Exception e) {
            throw new BusinessException(400, "请求体解析失败");
        }
    }
}
