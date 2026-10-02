package com.chronic.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网关 JWT 验签基础测试。与生产代码同源：密钥 ≥32 字节原始 UTF-8 字节（jjwt 0.11 语义），
 * 签发与解析均为 0.11 API。AuthFilter 的过滤器行为（白名单/身份头注入/类型校验）
 * 属 WebFlux 集成测试范畴，此处先覆盖签名链路本身。
 */
class AuthFilterTest {

    /** 与 AuthFilter 缺省开发密钥保持一致（≥32 字节） */
    private static final String SECRET = "chronic-disease-dev-secret-key-2026";

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("type", "access");
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L))
                .signWith(key)
                .compact();
    }

    private Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    @Test
    void getOrder_shouldReturnMinus100() {
        // 本用例只读过滤器常量，依赖传 null 即可
        AuthFilter filter = new AuthFilter(null, SECRET);
        assertEquals(-100, filter.getOrder());
    }

    @Test
    void generateToken_thenParse_shouldWork() {
        String token = generateToken(1L, "testuser");
        assertNotNull(token);

        Claims claims = parse(token);
        assertEquals(1L, claims.get("userId", Long.class));
        assertEquals("testuser", claims.get("username", String.class));
        assertEquals("access", claims.get("type", String.class));
    }

    @Test
    void tokenFromHeader_shouldBeExtractable() {
        String token = generateToken(99L, "gateway_user");
        String authHeader = "Bearer " + token;
        assertTrue(authHeader.startsWith("Bearer "));
        String extracted = authHeader.substring(7);
        assertEquals(token, extracted);

        Claims claims = parse(extracted);
        assertEquals(99L, claims.get("userId", Long.class));
    }

    @Test
    void invalidToken_shouldThrowException() {
        assertThrows(Exception.class, () -> parse("invalid.token.here"));
    }

    @Test
    void shortSecret_shouldBeRejected() {
        // jjwt 0.11 对 HMAC-SHA256 密钥强制 ≥32 字节，弱密钥启动即失败（快速失败）
        assertThrows(Exception.class,
                () -> Keys.hmacShaKeyFor("too-short-secret".getBytes(StandardCharsets.UTF_8)));
    }

    // ===== J-11 回归防护 =====
    // 缺陷背景：AuthFilter 的 @Value 曾带内联默认值
    // `${chronic.jwt.secret:chronic-disease-dev-secret-key-2026}`，
    // 而该默认值是公开仓库中的固定字符串 —— 任何读到源码的人都可据此伪造任意 userId 的 token，
    // 网关会把 claims.userId 当可信身份注入 X-User-Id，属完整身份伪造。
    // 且 AuthFilter 有默认值（静默回退）而 JwtUtil 无默认值（启动失败）：
    // 同一密钥、两个组件、两种失败语义。修复后网关同样 fail-fast。

    @Test
    void emptySecret_shouldRejectFilterConstruction_failFast() {
        assertThrows(IllegalStateException.class, () -> new AuthFilter(null, ""));
    }

    @Test
    void blankSecret_shouldRejectFilterConstruction_failFast() {
        assertThrows(IllegalStateException.class, () -> new AuthFilter(null, "   "));
    }

    @Test
    void nullSecret_shouldRejectFilterConstruction_failFast() {
        assertThrows(IllegalStateException.class, () -> new AuthFilter(null, null));
    }

    @Test
    void strongSecret_shouldBeAccepted() {
        // 合法密钥仍可正常构造（不得过度修复）
        AuthFilter filter = new AuthFilter(null, SECRET);
        assertNotNull(filter);
    }
}
