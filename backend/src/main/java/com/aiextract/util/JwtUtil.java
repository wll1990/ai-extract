package com.aiextract.util;

import com.aiextract.config.RolePermissions;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 注册邀请令牌有效期：30 分钟，仅用于公开页到注册接口的一次跳转 */
    public static final long REGISTER_INVITE_TTL_MILLIS = 30 * 60 * 1000L;

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
     * 生成JWT Token（自定义有效期 + 安全版本号）。
     *
     * <p>C 端 token 无企业归属：companyId 传 null 时不写入 claim，
     * 解析方（JwtAuthFilter）不读取 companyId，B 端专属接口已由 SecurityConfig 按角色拦截。</p>
     *
     * <p>{@code tokenVersion} 是用户的安全版本号：改角色/停用账号时 DB 里 +1，
     * 旧 token 携带的旧版本号与当前值不符即失效。参数设为必填（不提供默认重载），
     * 保证新增签发点不会漏传而导致「刚签发就失效」。</p>
     *
     * @param userId       用户ID
     * @param companyId    企业ID（可为 null — C 端平台级用户）
     * @param role         用户角色（B 端 super_admin/employee，C 端 c_guest/c_user）
     * @param ttlMillis    有效期毫秒
     * @param tokenVersion 用户当前安全版本号（user.tokenVersion）
     * @return JWT Token字符串
     */
    public String generateToken(UUID userId, UUID companyId, String role,
                                long ttlMillis, long tokenVersion) {
        return generateToken(userId, companyId, role, ttlMillis, tokenVersion, false);
    }

    /**
     * 生成登录 Token（带「必须修改密码」标记）。
     *
     * @param mustChangePassword 管理员重置/首次登录时为 true，过滤器只放行改密相关路径
     */
    public String generateToken(UUID userId, UUID companyId, String role,
                                long ttlMillis, long tokenVersion, boolean mustChangePassword) {
        Date now = new Date();
        Date expirationDate = new Date(now.getTime() + ttlMillis);

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId.toString());
        if (companyId != null) {
            claims.put("companyId", companyId.toString());
        }
        claims.put("role", role);
        // 自包含：签发时解析角色→权限，token 内嵌
        claims.put("permissions", RolePermissions.getPermissions(role));
        // 会话标识：支持精确撤销单 token
        claims.put("jti", UUID.randomUUID().toString());
        // 安全版本号：支持「改权限后旧 token 立即失效」
        claims.put("ver", tokenVersion);
        if (mustChangePassword) {
            claims.put("mcp", true);
        }

        String token = Jwts.builder()
                .claims(claims)
                .subject(userId.toString())
                .issuedAt(now)
                .expiration(expirationDate)
                .signWith(secretKey)
                .compact();

        log.debug("JWT Token已生成, userId: {}, ver: {}", userId, tokenVersion);
        return token;
    }

    /**
     * 生成注册邀请令牌 —— 公开分享页/访谈邀请页用。
     *
     * <p>只携带企业归属，不含 userId/role，不能作为登录 token 使用；
     * 匿名注册必须携带此令牌，服务端从令牌解析 companyId，忽略请求体。</p>
     *
     * @param companyId 企业 ID
     * @param source    来源（share/interview）
     * @param ttlMillis 有效期毫秒
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
        String companyIdStr = claims.get("companyId", String.class);
        return companyIdStr != null ? UUID.fromString(companyIdStr) : null;
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

    /** 是否要求先修改密码（管理员重置/首次登录） */
    public boolean mustChangePasswordFromToken(String token) {
        Claims claims = parseToken(token);
        return Boolean.TRUE.equals(claims.get("mcp", Boolean.class));
    }

    /**
     * 从Token中提取权限码集合（自包含 claim）；旧 token 无此 claim 返回空集合。
     */
    @SuppressWarnings("unchecked")
    public Set<String> getPermissionsFromToken(String token) {
        Claims claims = parseToken(token);
        List<String> perms = claims.get("permissions", List.class);
        return perms == null ? Set.of() : new HashSet<>(perms);
    }

    /**
     * 从Token中提取会话标识 jti。
     */
    public String getJtiFromToken(String token) {
        return parseToken(token).get("jti", String.class);
    }

    /**
     * 从Token中提取过期时间。
     */
    public Date getExpirationFromToken(String token) {
        return parseToken(token).getExpiration();
    }

    /**
     * 从Token中提取安全版本号。
     *
     * <p>改造前签发的 token 没有 {@code ver} claim，按 0 处理；
     * 用户在版本号 bump 之前保持兼容，bump 之后自动失效。</p>
     *
     * @return 版本号；无该 claim 返回 0
     */
    public long getTokenVersionFromToken(String token) {
        Number ver = parseToken(token).get("ver", Number.class);
        return ver == null ? 0L : ver.longValue();
    }

    /** 默认有效期（毫秒），供新增签发点复用 */
    public long getDefaultExpirationMillis() {
        return expiration;
    }

}
