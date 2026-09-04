package com.aiextract.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * IM 桥内部鉴权 — HMAC-SHA256 签名（backend ↔ 传输层对称）。
 *
 * <p>签名串：{@code timestamp + "\n" + nonce + "\n" + body}，请求头携带
 * {@code X-Im-Timestamp / X-Im-Nonce / X-Im-Signature}。</p>
 *
 * <p>传输层（Node 飞书桥）用同一 secret 实现相同算法，互相验签。
 * 当前单密钥（env 注入），后续升级双密钥轮换。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@Component
public class ImBridgeAuth {

    private final String secret;

    public ImBridgeAuth(@Value("${app.im.bridge-token}") String secret) {
        this.secret = secret;
    }

    /**
     * 校验请求签名。
     *
     * @param timestamp 时间戳（秒）
     * @param nonce     随机数
     * @param body      请求体原文
     * @param signature 客户端签名
     * @return true 通过
     */
    public boolean verify(String timestamp, String nonce, String body, String signature) {
        if (timestamp == null || nonce == null || signature == null) {
            return false;
        }
        String expected = sign(timestamp, nonce, body);
        return constantTimeEquals(expected, signature);
    }

    /**
     * 为出站请求生成签名头（backend → 传输层）。
     *
     * @param body 请求体原文
     * @return 头映射（X-Im-Timestamp / X-Im-Nonce / X-Im-Signature）
     */
    public Map<String, String> signHeaders(String body) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Im-Timestamp", timestamp);
        headers.put("X-Im-Nonce", nonce);
        headers.put("X-Im-Signature", sign(timestamp, nonce, body));
        return headers;
    }

    private String sign(String timestamp, String nonce, String body) {
        try {
            String data = timestamp + "\n" + nonce + "\n" + (body == null ? "" : body);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("IM 签名计算失败", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
