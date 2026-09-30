package com.chronic.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT（JSON Web Token）工具类 —— 双令牌机制（业界通用做法）
 * <p>
 * access token：短效（默认 2 小时），每次请求携带，网关验签后放行；
 * refresh token：长效（默认 7 天），仅在调 /user/refresh 续期时使用，
 * 网关拒绝其作为访问凭证使用（type 声明区分）。
 * </p>
 * <p>
 * 签名密钥从 Spring 配置项 chronic.jwt.secret 读取（与网关 AuthFilter 同名配置项），
 * 可用 jar 旁 config/application.yml 外部配置覆盖。
 * jjwt 0.11.x 直接把 UTF-8 字节作为 HMAC 密钥（HMAC-SHA256 要求 ≥32 字节，
 * 不足 32 字节启动即抛 WeakKeyException，属预期的快速失败），
 * 生产环境必须配置 ≥32 字节的强随机值，且与历史 0.9.1 时代令牌不互认（需重新登录）。
 * </p>
 *
 * @author chronic
 */
@Component
public class JwtUtil {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    /** 管理员角色值（签发进 role 声明，网关对 /api/admin/** 集中校验） */
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    private static final String ISSUER = "chronic";

    /** HMAC-SHA256 签名密钥（由配置的字符串派生） */
    private final SecretKey key;

    /** access token 有效期（分钟） */
    private final long accessExpireMinutes;

    /** refresh token 有效期（天） */
    private final long refreshExpireDays;

    public JwtUtil(@Value("${chronic.jwt.secret}") String secret,
                   @Value("${chronic.jwt.access-expire-minutes:120}") long accessExpireMinutes,
                   @Value("${chronic.jwt.refresh-expire-days:7}") long refreshExpireDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpireMinutes = accessExpireMinutes;
        this.refreshExpireDays = refreshExpireDays;
    }

    /**
     * 生成 access token（兼容旧调用）
     */
    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, TYPE_ACCESS);
    }

    /**
     * 生成指定类型的 JWT Token，声明包含 iss、type、sub、iat、exp、**jti** 与 **role**
     * <p><b>为什么要加 jti（JWT ID）</b>：</p>
     * <p>jti 是 JWT 规范里为「令牌唯一标识 / 吊销」预留的标准声明。没有它时，
     * 同一秒内为同一用户签发的同类型令牌，其全部声明（userId/username/type/iss/iat/exp）
     * 完全一致 —— 因为 iat/exp 精度只到<b>秒</b> —— 于是签出来的**字符串一模一样**。</p>
     * <p>这在 refresh token 轮换场景下会直接出问题：
     * 轮换时要「拉黑旧令牌、签发新令牌」，而新旧令牌字符串相同，
     * 黑名单按令牌摘要存储，拉黑旧的等于把新的也一起拉黑了，
     * 用户续期一次就会被登出。</p>
     * <p>加上随机 jti 后，每枚令牌都唯一，轮换与黑名单语义才成立。</p>
     *
     * @param userId   用户ID
     * @param username 用户名
     * @param type     TYPE_ACCESS / TYPE_REFRESH
     * @param role     角色（USER / ADMIN），签发时固化进令牌，网关对 /api/admin/** 集中校验
     * @return JWT Token 字符串
     */
    public String generateToken(Long userId, String username, String type, String role) {
        long expireMs = TYPE_REFRESH.equals(type)
                ? refreshExpireDays * 24 * 60 * 60 * 1000L
                : accessExpireMinutes * 60 * 1000L;
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("type", type);
        claims.put("role", role == null || role.isBlank() ? ROLE_USER : role);
        return Jwts.builder()
                .setClaims(claims)
                // 唯一标识：保证同一秒内签发的多枚令牌互不相同（轮换/黑名单依赖它）
                .setId(UUID.randomUUID().toString())
                .setIssuer(ISSUER)
                .setSubject(String.valueOf(userId))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expireMs))
                .signWith(key)
                .compact();
    }

    /** 兼容旧调用：未指定角色按普通用户签发 */
    public String generateToken(Long userId, String username, String type) {
        return generateToken(userId, username, type, ROLE_USER);
    }

    /**
     * 解析 Token 并返回 Claims
     *
     * @param token JWT Token
     * @return Token 中的声明信息
     * @throws io.jsonwebtoken.JwtException Token 无效或过期时抛出
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .requireIssuer(ISSUER)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 判断 Token 是否已过期
     *
     * @param token JWT Token
     * @return true 表示已过期或无效
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 从 Token 中提取用户ID
     *
     * @param token JWT Token
     * @return 用户ID，解析失败返回 null
     */
    public Long getUserId(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.get("userId", Long.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 Token 中提取用户名
     *
     * @param token JWT Token
     * @return 用户名，解析失败返回 null
     */
    public String getUsername(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.get("username", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 Token 中提取角色（USER / ADMIN）；解析失败或旧令牌无 role 声明返回 USER
     */
    public String getRole(String token) {
        try {
            String role = parseToken(token).get("role", String.class);
            return role == null || role.isBlank() ? ROLE_USER : role;
        } catch (Exception e) {
            return ROLE_USER;
        }
    }
}
