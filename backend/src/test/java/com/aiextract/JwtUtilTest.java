package com.aiextract;

import com.aiextract.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 注册邀请令牌测试 —— 只覆盖公开页到注册接口的一次性凭证。
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-key-for-jwt-0123456789abcdef";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        jwtUtil.init();
    }

    @Test
    void inviteTokenCarriesCompany() {
        UUID companyId = UUID.randomUUID();
        String token = jwtUtil.generateInviteToken(companyId, "share",
                JwtUtil.REGISTER_INVITE_TTL_MILLIS);

        assertEquals(companyId, jwtUtil.parseInviteToken(token));
    }

    @Test
    void expiredInviteToken_isRejected() {
        String token = jwtUtil.generateInviteToken(UUID.randomUUID(), "share", -1_000L);

        assertThrows(Exception.class, () -> jwtUtil.parseInviteToken(token));
    }

    @Test
    void nonInviteToken_isRejected() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .claim("typ", "login")
                .claim("companyId", UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();

        assertThrows(IllegalArgumentException.class, () -> jwtUtil.parseInviteToken(token));
    }

    @Test
    void inviteTokenWithoutCompany_isRejected() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .claim("typ", "register_invite")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();

        assertThrows(IllegalArgumentException.class, () -> jwtUtil.parseInviteToken(token));
    }
}
