package com.chronic.common.utils;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    /** 测试专用密钥（纯字母数字，≥32 字节等价强度），生产通过 chronic.jwt.secret 配置覆盖 */
    private static final String TEST_SECRET = "testsecret0123456789abcdefghijklmnopqrstuvwxyz";

    private final JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, 120, 7);

    private final SecretKey testKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    @Test
    void generateToken_shouldReturnNonNullToken() {
        String token = jwtUtil.generateToken(1L, "testuser");
        assertNotNull(token);
        assertTrue(token.length() > 0);
    }

    @Test
    void generateToken_shouldCarryTypeClaim() {
        // 双令牌：access/refresh 必须带 type 声明，网关凭此拒绝 refresh token 访问业务接口
        assertEquals("access", jwtUtil.parseToken(jwtUtil.generateToken(1L, "u")).get("type", String.class));
        assertEquals("refresh", jwtUtil.parseToken(
                jwtUtil.generateToken(1L, "u", JwtUtil.TYPE_REFRESH)).get("type", String.class));
    }

    @Test
    void parseToken_shouldReturnClaims() {
        String token = jwtUtil.generateToken(100L, "admin");
        io.jsonwebtoken.Claims claims = jwtUtil.parseToken(token);
        assertNotNull(claims);
        assertEquals(100L, claims.get("userId", Long.class));
        assertEquals("admin", claims.get("username", String.class));
    }

    @Test
    void isTokenExpired_shouldReturnFalse_forNewToken() {
        String token = jwtUtil.generateToken(1L, "user");
        assertFalse(jwtUtil.isTokenExpired(token));
    }

    @Test
    void isTokenExpired_shouldReturnTrue_forInvalidToken() {
        assertTrue(jwtUtil.isTokenExpired("invalid.token.here"));
    }

    @Test
    void getUserId_shouldReturnUserId() {
        String token = jwtUtil.generateToken(42L, "john");
        assertEquals(42L, jwtUtil.getUserId(token));
    }

    @Test
    void getUserId_shouldReturnNull_forInvalidToken() {
        assertNull(jwtUtil.getUserId("bad"));
    }

    @Test
    void getUsername_shouldReturnUsername() {
        String token = jwtUtil.generateToken(1L, "alice");
        assertEquals("alice", jwtUtil.getUsername(token));
    }

    @Test
    void getUsername_shouldReturnNull_forInvalidToken() {
        assertNull(jwtUtil.getUsername("bad"));
    }

    @Test
    void parseToken_shouldThrow_forExpiredToken() {
        // 用与被测实例相同的密钥签发一个已过期 Token：签名正确、纯粹因过期被拒
        String expired = Jwts.builder()
                .setClaims(new HashMap<>(Map.of("userId", 1L)))
                .setIssuedAt(new Date(System.currentTimeMillis() - 7200_000L))
                .setExpiration(new Date(System.currentTimeMillis() - 3600_000L))
                .signWith(testKey)
                .compact();
        assertTrue(jwtUtil.isTokenExpired(expired));
        assertThrows(Exception.class, () -> jwtUtil.parseToken(expired));
    }

    @Test
    void parseToken_shouldThrow_forTamperedSignature() {
        String token = jwtUtil.generateToken(1L, "alice");
        // 篡改签名末位字符后必须解析失败（防伪造）
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("A") ? "B" : "A");
        assertThrows(Exception.class, () -> jwtUtil.parseToken(tampered));
        assertNull(jwtUtil.getUserId(tampered));
    }

    // ===== J-17 回归：令牌必须唯一（jti）=====
    // 没有 jti 时，同一秒内为同一用户签发的同类型令牌，其声明完全一致
    //（iat/exp 精度只到秒），字符串也完全一样。
    // 这会让「轮换 refresh token」失效：拉黑旧令牌等于把新令牌一起拉黑，
    // 用户续期一次就会被登出。

    @Test
    void generateToken_shouldBeUnique_forSameUserSameSecond() {
        String first = jwtUtil.generateToken(1L, "alice", JwtUtil.TYPE_REFRESH);
        String second = jwtUtil.generateToken(1L, "alice", JwtUtil.TYPE_REFRESH);

        assertNotEquals(first, second, "同一秒内签发的两枚令牌必须不同（依赖 jti）");
        // 两枚令牌的 jti 必须不同，且都非空
        assertNotNull(jwtUtil.parseToken(first).getId());
        assertNotNull(jwtUtil.parseToken(second).getId());
        assertNotEquals(jwtUtil.parseToken(first).getId(), jwtUtil.parseToken(second).getId());
    }

    @Test
    void generateToken_shouldKeepSameClaims_afterAddingJti() {
        // 加 jti 不应影响既有声明（避免破坏网关等既有解析方）
        String token = jwtUtil.generateToken(7L, "bob", JwtUtil.TYPE_ACCESS);
        var claims = jwtUtil.parseToken(token);
        assertEquals(7L, claims.get("userId", Long.class));
        assertEquals("bob", claims.get("username", String.class));
        assertEquals(JwtUtil.TYPE_ACCESS, claims.get("type", String.class));
        assertEquals("chronic", claims.getIssuer());
    }

    @Test
    void parseToken_shouldThrow_forTokenSignedWithDifferentSecret() {
        // 攻击者用自己的密钥（合法强度）签发令牌，我方验签必须失败
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "attacker-controlled-secret-0123456789".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .setClaims(new HashMap<>(Map.of("userId", 999L, "username", "attacker")))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600_000L))
                .signWith(attackerKey)
                .compact();
        assertThrows(Exception.class, () -> jwtUtil.parseToken(forged));
        assertNull(jwtUtil.getUserId(forged));
    }

    @Test
    void parseToken_shouldFail_whenSecretRotated() {
        // 密钥轮换场景：旧密钥签发的 token 在换新密钥的实例上必须失效
        JwtUtil rotated = new JwtUtil("rotatedsecret0123456789abcdefghijklmnopqrstuvwxyz", 120, 7);
        String oldToken = jwtUtil.generateToken(1L, "alice");
        assertThrows(Exception.class, () -> rotated.parseToken(oldToken));
    }

    @Test
    void roleClaim_shouldRoundTrip() {
        // 管理端权限底座：role 签发进 claims，getRole 读取；网关据此校验 /api/admin/**
        String adminToken = jwtUtil.generateToken(1L, "admin", JwtUtil.TYPE_ACCESS, JwtUtil.ROLE_ADMIN);
        assertEquals(JwtUtil.ROLE_ADMIN, jwtUtil.getRole(adminToken));

        String userToken = jwtUtil.generateToken(2L, "bob", JwtUtil.TYPE_ACCESS, JwtUtil.ROLE_USER);
        assertEquals(JwtUtil.ROLE_USER, jwtUtil.getRole(userToken));
    }

    @Test
    void roleClaim_shouldDefaultToUser_whenLegacyOrInvalid() {
        // 旧令牌（无 role 声明）与解析失败场景一律按普通用户处理（fail-closed：不给管理权限）
        String legacyToken = jwtUtil.generateToken(3L, "carol");
        assertEquals(JwtUtil.ROLE_USER, jwtUtil.getRole(legacyToken));
        assertEquals(JwtUtil.ROLE_USER, jwtUtil.getRole("not-a-token"));
    }
}
