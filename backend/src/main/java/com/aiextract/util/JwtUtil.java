package com.aiextract.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 注册邀请令牌工具。
 *
 * <p>只负责公开分享页/访谈邀请页到注册接口的一次性跳转；用户登录态已迁到
 * Redis 会话（{@code SessionService}），本类不再签发或校验用户 token。</p>
 */
@Slf4j
@Component
public class JwtUtil {

    /** 注册邀请令牌有效期：30 分钟，仅用于公开页到注册接口的一次跳转 */
    public static final long REGISTER_INVITE_TTL_MILLIS = 30 * 60 * 1000L;

    @Value("${jwt.secret}")
    private String secret;

    private SecretKey secretKey;
    private JwtParser jwtParser;

    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank() || secret.startsWith("${")) {
            throw new IllegalStateException(
                    "JWT_SECRET 未配置！请设置环境变量 JWT_SECRET 或在 application.yml 中配置 jwt.secret");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.jwtParser = Jwts.parser().verifyWith(secretKey).build();
        log.info("注册邀请令牌工具初始化完成");
    }

    /**
     * 生成注册邀请令牌 —— 公开分享页/访谈邀请页用。
     *
     * <p>只携带企业归属，不含 userId/role，不能作为登录 token 使用；
     * 匿名注册必须携带此令牌，服务端从令牌解析 companyId，忽略请求体。</p>
     */
    public String generateInviteToken(UUID companyId, String source, long ttlMillis) {
        Date now = new Date();
        Map<String, Object> claims = new HashMap<>();
        claims.put("typ", "register_invite");
        claims.put("companyId", companyId.toString());
        claims.put("src", source);
        claims.put("jti", UUID.randomUUID().toString());
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 校验注册邀请令牌并返回企业 ID。
     *
     * @throws IllegalArgumentException 令牌无效、过期或类型不符
     */
    public UUID parseInviteToken(String token) {
        Claims claims = parseToken(token);
        if (!"register_invite".equals(claims.get("typ", String.class))) {
            throw new IllegalArgumentException("不是注册邀请令牌");
        }
        String companyId = claims.get("companyId", String.class);
        if (companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException("邀请令牌缺少企业信息");
        }
        return UUID.fromString(companyId);
    }

    private Claims parseToken(String token) {
        return jwtParser.parseSignedClaims(token).getPayload();
    }
}
