package com.aiextract.service.partner;

import com.aiextract.config.PartnerCrypto;
import com.aiextract.exception.PartnerException;
import com.aiextract.model.PartnerApp;
import com.aiextract.repository.PartnerAppRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 合作方服务端 HMAC 验签。
 *
 * <p>待签名串：{@code METHOD\nPATH\nTIMESTAMP\nNONCE\nsha256(rawBody)}；
 * 时间窗 ±5 分钟；nonce 10 分钟内不可重复；SK 支持 current/old 轮换。</p>
 */
@Component
@RequiredArgsConstructor
public class PartnerHmacVerifier {

    private static final String HEADER_APP = "X-Partner-App-Id";
    private static final String HEADER_TS = "X-Partner-Timestamp";
    private static final String HEADER_NONCE = "X-Partner-Nonce";
    private static final String HEADER_SIGNATURE = "X-Partner-Signature";
    private static final long WINDOW_MS = 5 * 60 * 1000L;

    private final PartnerAppRepository partnerAppRepository;
    private final PartnerCrypto partnerCrypto;
    private final StringRedisTemplate redis;

    public PartnerApp verify(HttpServletRequest request, String rawBody) {
        String appId = request.getHeader(HEADER_APP);
        String timestamp = request.getHeader(HEADER_TS);
        String nonce = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);
        if (isBlank(appId) || isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            throw invalid("缺少合作方签名头");
        }

        PartnerApp app = partnerAppRepository.findByAppId(appId)
                .orElseThrow(PartnerException::appNotFound);
        if (app.getStatus() != PartnerApp.PartnerStatus.ENABLED) {
            throw PartnerException.appDisabled();
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw invalid("时间戳格式错误");
        }
        if (Math.abs(System.currentTimeMillis() - ts) > WINDOW_MS) {
            throw invalid("时间戳超出允许窗口");
        }

        String canonical = request.getMethod() + "\n"
                + request.getRequestURI() + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + sha256Hex(rawBody == null ? "" : rawBody);

        String primary = hmacHex(partnerCrypto.decrypt(app.getSecretKey()), canonical);
        boolean valid = constantTimeEquals(primary, signature);
        if (!valid && app.getOldSecretKey() != null
                && app.getOldKeyExpiresAt() != null
                && app.getOldKeyExpiresAt().isAfter(LocalDateTime.now())) {
            String old = hmacHex(partnerCrypto.decrypt(app.getOldSecretKey()), canonical);
            valid = constantTimeEquals(old, signature);
        }
        if (!valid) {
            throw new PartnerException(401, "PARTNER_SK_MISMATCH", "签名校验失败");
        }

        // 验签通过后才写 nonce：否则未授权的请求可以无限写入 Redis key。
        Boolean first = redis.opsForValue().setIfAbsent(
                "partner:nonce:" + appId + ":" + nonce, "1", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(first)) {
            throw invalid("nonce 已使用");
        }
        return app;
    }

    private static PartnerException invalid(String message) {
        return new PartnerException(401, "PARTNER_SIGNATURE_INVALID", message);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacHex(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
