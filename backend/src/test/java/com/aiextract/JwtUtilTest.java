package com.aiextract;

import com.aiextract.config.Permission;
import com.aiextract.config.RolePermissions;
import com.aiextract.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtUtil 单元测试 —— 安全版本号 claim 的写入/读取，以及改造前旧 token 的兼容。
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-key-for-jwt-0123456789abcdef";
    private static final long TTL = 3_600_000L;

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", TTL);
        jwtUtil.init();
    }

    @Test
    void tokenCarriesVersionAndPermissions() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        String token = jwtUtil.generateToken(userId, companyId,
                RolePermissions.COMPANY_ADMIN, TTL, 7L);

        assertEquals(7L, jwtUtil.getTokenVersionFromToken(token));
        assertEquals(userId, jwtUtil.getUserIdFromToken(token));
        assertEquals(companyId, jwtUtil.getCompanyIdFromToken(token));
        assertEquals(RolePermissions.COMPANY_ADMIN, jwtUtil.getRoleFromToken(token));
        assertTrue(jwtUtil.getPermissionsFromToken(token).contains(Permission.EVAL_MANAGE));
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    void inviteTokenCarriesCompanyAndIsNotLoginToken() {
        UUID companyId = UUID.randomUUID();
        String token = jwtUtil.generateInviteToken(companyId, "share",
                JwtUtil.REGISTER_INVITE_TTL_MILLIS);

        assertEquals(companyId, jwtUtil.parseInviteToken(token));
        assertThrows(Exception.class, () -> jwtUtil.getUserIdFromToken(token));
    }

    @Test
    void loginTokenCannotBeUsedAsInviteToken() {
        String token = jwtUtil.generateToken(UUID.randomUUID(), UUID.randomUUID(),
                RolePermissions.EMPLOYEE, TTL, 0L);

        assertThrows(IllegalArgumentException.class, () -> jwtUtil.parseInviteToken(token));
    }

    @Test
    void legacyTokenWithoutVersionClaim_readsAsZero() {
        // 改造前签发的 token 没有 ver claim：必须按 0 处理，否则上线即让所有在线用户掉线
        UUID userId = UUID.randomUUID();
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String legacy = Jwts.builder()
                .claim("userId", userId.toString())
                .claim("role", RolePermissions.EMPLOYEE)
                .subject(userId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + TTL))
                .signWith(key)
                .compact();

        assertTrue(jwtUtil.validateToken(legacy));
        assertEquals(0L, jwtUtil.getTokenVersionFromToken(legacy));
    }

    @Test
    void cEndTokenWithoutCompany_keepsNullCompanyId() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.generateToken(userId, null, "c_guest", TTL, 0L);

        assertEquals(userId, jwtUtil.getUserIdFromToken(token));
        assertNull(jwtUtil.getCompanyIdFromToken(token));
        assertEquals(0L, jwtUtil.getTokenVersionFromToken(token));
    }

    @Test
    void expiredToken_isInvalid() {
        String token = jwtUtil.generateToken(UUID.randomUUID(), null,
                RolePermissions.EMPLOYEE, -1_000L, 0L);

        assertFalse(jwtUtil.validateToken(token));
    }

    @Test
    void permissionSetIsStableAcrossRegeneration() {
        Set<String> first = jwtUtil.getPermissionsFromToken(jwtUtil.generateToken(
                UUID.randomUUID(), null, RolePermissions.EMPLOYEE, TTL, 1L));
        Set<String> second = jwtUtil.getPermissionsFromToken(jwtUtil.generateToken(
                UUID.randomUUID(), null, RolePermissions.EMPLOYEE, TTL, 1L));

        assertEquals(first, second);
        assertFalse(first.contains(Permission.EVAL_MANAGE), "employee 不应拿到评测管理权限");
    }
}
