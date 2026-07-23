package com.aiextract.config;

import com.aiextract.exception.PartnerException;
import com.aiextract.model.PartnerApp;
import com.aiextract.model.PartnerApp.PartnerStatus;
import com.aiextract.model.User;
import com.aiextract.repository.PartnerAppRepository;
import com.aiextract.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * 合作方 JWT 验证器 — 解析 ?token= 参数，验证签名，自动注册/查找用户。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerJwtFilter {

    private final PartnerAppRepository partnerAppRepository;
    private final PartnerCrypto partnerCrypto;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证合作方 JWT，返回我方用户 UUID（新用户自动创建）。
     */
    public UUID authenticate(String rawToken) {
        // 1. 不解签名先读 appId
        String appId = parseAppIdWithoutVerify(rawToken);
        PartnerApp app = partnerAppRepository.findByAppId(appId)
            .orElseThrow(PartnerException::appNotFound);

        if (app.getStatus() != PartnerStatus.ENABLED) {
            throw PartnerException.appDisabled();
        }

        // 2. 用当前 SK + 旧 SK（过渡期）分别验证
        String currentSK = partnerCrypto.decrypt(app.getSecretKey());
        Claims claims = verifyToken(rawToken, currentSK,
            app.getOldSecretKey() != null ? partnerCrypto.decrypt(app.getOldSecretKey()) : null);

        String externalUserId = claims.get("userId", String.class);
        String userName = claims.get("userName", String.class);
        if (externalUserId == null || externalUserId.isBlank()) {
            throw PartnerException.tokenInvalid("缺少 userId 字段");
        }

        // 3. account = partner:{appId}:{externalUserId}
        String account = "partner:" + appId + ":" + externalUserId;
        User user = findOrCreateUser(account, userName != null ? userName : externalUserId);

        log.info("Partner auth success: appId={} externalUserId={} internalId={}",
            appId, externalUserId, user.getId());
        return user.getId();
    }

    private String parseAppIdWithoutVerify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) throw PartnerException.tokenInvalid("JWT 格式错误");
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);
            String appId = payload.has("appId") ? payload.get("appId").asText() : null;
            if (appId == null || appId.isBlank()) throw PartnerException.tokenInvalid("缺少 appId 字段");
            return appId;
        } catch (PartnerException e) { throw e; }
        catch (Exception e) {
            throw PartnerException.tokenInvalid("JWT 解析失败: " + e.getMessage());
        }
    }

    private Claims verifyToken(String token, String primarySK, String fallbackSK) {
        try {
            return parseWithSK(token, primarySK);
        } catch (ExpiredJwtException e) {
            throw PartnerException.tokenExpired();
        } catch (SignatureException e) {
            if (fallbackSK != null) {
                try { return parseWithSK(token, fallbackSK); }
                catch (Exception e2) { throw PartnerException.skMismatch(); }
            }
            throw PartnerException.skMismatch();
        } catch (Exception e) {
            throw PartnerException.tokenInvalid(e.getMessage());
        }
    }

    private Claims parseWithSK(String token, String sk) {
        SecretKeySpec key = new SecretKeySpec(sk.getBytes(), "HmacSHA256");
        return Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).getPayload();
    }

    private User findOrCreateUser(String account, String name) {
        // 查 account 字段（企业内唯一，合作方用户用 partner:appId:userId 格式）
        Optional<User> existing = userRepository.findByCompanyIdAndAccount(
            getPartnerCompanyId(), account);
        if (existing.isPresent()) return existing.get();

        User user = User.builder()
            .id(UUID.randomUUID())
            .companyId(getPartnerCompanyId())
            .account(account)
            .name(name)
            .role("c_partner")
            .passwordHash("")
            .isActive(true)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
        return userRepository.save(user);
    }

    /** 合作方用户的统一 companyId — 不需要真实企业，用一个固定的 UUID */
    private static final UUID PARTNER_COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private UUID getPartnerCompanyId() {
        return PARTNER_COMPANY_ID;
    }
}
