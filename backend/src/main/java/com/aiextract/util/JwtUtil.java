package com.aiextract.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
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
 * JWT工具类
 *
 * <p>
 * 负责JWT Token的生成、验证和解析。Token中包含userId、companyId和role三个核心字段。
 * 密钥和过期时间从application.yml配置中读取。
 * </p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey secretKey;

    private JwtParser jwtParser;

    /**
     * 初始化密钥和JWT解析器
     */
    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank()
            || secret.startsWith("${")) {
            throw new IllegalStateException(
                "JWT_SECRET 未配置！请设置环境变量 JWT_SECRET 或在 application.yml 中配置 jwt.secret");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.jwtParser = Jwts.parser()
                .verifyWith(secretKey)
                .build();
        log.info("JWT工具初始化完成, Token有效期: {}ms", expiration);
    }

    /**
     * 生成JWT Token
     *
     * @param userId    用户ID
     * @param companyId 企业ID
     * @param role      用户角色
     * @return JWT Token字符串
     */
    public String generateToken(UUID userId, UUID companyId, String role) {
        return generateToken(userId, companyId, role, expiration);
    }

    /**
     * 生成JWT Token（自定义有效期）
     *
     * <p>C 端 token 无企业归属：companyId 传 null 时不写入 claim，
     * 解析方（JwtAuthFilter）不读取 companyId，B 端专属接口已由 SecurityConfig 按角色拦截。</p>
     *
     * @param userId    用户ID
     * @param companyId 企业ID（可为 null — C 端平台级用户）
     * @param role      用户角色（B 端 super_admin/employee，C 端 c_guest/c_user）
     * @param ttlMillis 有效期毫秒
     * @return JWT Token字符串
     */
    public String generateToken(UUID userId, UUID companyId, String role, long ttlMillis) {
        Date now = new Date();
        Date expirationDate = new Date(now.getTime() + ttlMillis);

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId.toString());
        if (companyId != null) {
            claims.put("companyId", companyId.toString());
        }
        claims.put("role", role);

        String token = Jwts.builder()
                .claims(claims)
                .subject(userId.toString())
                .issuedAt(now)
                .expiration(expirationDate)
                .signWith(secretKey)
                .compact();

        log.debug("JWT Token已生成, userId: {}", userId);
        return token;
    }

    /**
     * 验证Token是否有效
     *
     * @param token JWT Token
     * @return true表示有效
     */
    public boolean validateToken(String token) {
        try {
            jwtParser.parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT Token已过期");
            return false;
        } catch (MalformedJwtException e) {
            log.warn("JWT Token格式错误");
            return false;
        } catch (Exception e) {
            log.error("JWT Token验证异常", e);
            return false;
        }
    }

    /**
     * 从Token中解析Claims
     *
     * @param token JWT Token
     * @return JWT Claims对象
     */
    public Claims parseToken(String token) {
        return jwtParser.parseSignedClaims(token).getPayload();
    }

    /**
     * 从Token中提取用户ID
     *
     * @param token JWT Token
     * @return 用户ID
     */
    public UUID getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return UUID.fromString(claims.get("userId", String.class));
    }

    /**
     * 从Token中提取企业ID
     *
     * @param token JWT Token
     * @return 企业ID
     */
    public UUID getCompanyIdFromToken(String token) {
        Claims claims = parseToken(token);
        return UUID.fromString(claims.get("companyId", String.class));
    }

    /**
     * 从Token中提取用户角色
     *
     * @param token JWT Token
     * @return 用户角色
     */
    public String getRoleFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("role", String.class);
    }

}
