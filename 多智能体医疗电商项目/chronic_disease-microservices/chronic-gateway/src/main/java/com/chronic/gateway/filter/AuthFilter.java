package com.chronic.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.chronic.common.utils.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 网关鉴权过滤器 —— 整个系统的「门卫」。
 *
 * <h3>【小白先看：为什么需要它】</h3>
 * 前端只访问网关（8080），网关再把请求转发给后面的各个微服务。
 * 与其让每个微服务都自己验一遍登录，不如在门口统一验一次 —— 这就是本类的职责。
 *
 * <h3>【JWT 是什么，为什么安全】</h3>
 * 用户登录成功后，服务端发一张「电子通行证」（JWT，一串加密字符串）给前端。
 * 这张证里写着「我是用户 123」，并且用只有服务端知道的密钥签了名。
 * 之后前端每次请求都带上它（放在 Authorization 头里）。
 * 关键点：<b>签名无法伪造</b> —— 改了内容签名就对不上，所以网关可以信任证上的身份。
 * 但前提是<b>签名密钥不能泄露</b>，否则任何人都能自己造证（见下面第 4 条）。
 *
 * <h3>【本过滤器的四道关卡】</h3>
 * <ol>
 *   <li><b>白名单精确匹配</b>（含去末尾斜杠）：只有登录/注册/刷新等少数接口免鉴权。
 *       用精确匹配而非 startsWith，避免 /api/xxx 被 /api/x 之类的前缀误放行。</li>
 *   <li><b>剥离伪造身份头</b>：客户端可以自己往请求里塞 X-User-Id: 1 冒充别人，
 *       所以这里<b>先一律删掉</b>这两个头；只有验签通过后，才由本类根据 Token 里的
 *       userId 重新注入。顺序很重要 —— 必须"先删后注入"。</li>
 *   <li><b>验签 + 检查类型</b>：解析 JWT 并校验签名；只允许 type=access 的令牌访问业务接口
 *       （refresh 令牌只能用于续期，不能当通行证用）。</li>
 *   <li><b>密钥强制配置</b>（J-11 修复）：签名密钥必须由配置注入，<b>不提供默认值</b>。
 *       原先这里内联了一个公开的默认密钥，等于把"造证方法"写在了开源代码里 ——
 *       任何人拿到源码就能伪造任意用户的身份。现在缺配置会直接启动失败，
 *       宁可起不来，也不能带着一把人人都知道的钥匙运行。</li>
 * </ol>
 *
 * <h3>【黑名单：为什么登出能立刻生效】</h3>
 * JWT 是无状态的，签发后到过期前一直有效，"登出"没法让它作废。
 * 所以登出时把该 Token 记进 Redis 黑名单，每次请求都查一下。
 * 代价是多一次 Redis 查询；Redis 挂了则降级放行（可用性优先，黑名单属增强防护）。
 *
 * @author chronic
 */
@Slf4j
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    /** 需要剥离的客户端伪造身份头 */
    private static final Set<String> IDENTITY_HEADERS = Set.of("X-User-Id", "X-Username", "X-User-Role");

    /** 管理接口路径前缀：需 ADMIN 角色（role 签发进 JWT，网关集中校验，下游不重复判权） */
    private static final String ADMIN_PATH_PREFIX = "/api/admin/";

    /** 允许使用 URL 参数 token 的路径前缀 */
    private static final String QUERY_TOKEN_PATH_PREFIX = "/api/ai/";

    /** 无需鉴权的白名单路径（精确匹配） */
    private static final Set<String> EXCLUDE_PATHS = Set.of(
            "/api/user/login",
            "/api/user/register",
            "/api/user/refresh",
            "/api/ai/sources"
    );

    /** 令牌黑名单 key 前缀（登出/封禁），值为占位 1，TTL=令牌剩余有效期 */
    private static final String BLACKLIST_KEY_PREFIX = "jwt:bl:";

    /** HMAC-SHA256 验签密钥（由配置字符串派生） */
    private final SecretKey signingKey;

    private final ReactiveStringRedisTemplate redisTemplate;

    public AuthFilter(ReactiveStringRedisTemplate redisTemplate,
                      @Value("${chronic.jwt.secret}") String secret) {
        // J-11 修复：此处**不再提供内联默认值**。
        // 原先为 `${chronic.jwt.secret:chronic-disease-dev-secret-key-2026}`，该默认值是
        // 公开仓库中的固定字符串，任何拿到源码的人都能用它伪造任意 userId 的 token
        // （网关会把 claims.userId 作为可信身份注入 X-User-Id）。
        // 现改为强制要求配置：缺失时 Spring 占位符解析失败 -> 启动即失败（fail-fast），
        // 与 JwtUtil 的语义保持一致（同一密钥、两个组件、统一失败行为）。
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException(
                    "chronic.jwt.secret 未配置：网关拒绝以空密钥启动（请通过环境变量注入强随机值）");
        }
        this.redisTemplate = redisTemplate;
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 全局过滤器：校验请求路径是否在白名单中，非白名单请求需携带有效 JWT Token
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // 先剥离客户端伪造的身份头，白名单放行与鉴权通过都基于净化后的请求
        ServerHttpRequest stripped = request.mutate()
                .headers(h -> IDENTITY_HEADERS.forEach(h::remove))
                .build();
        String path = request.getURI().getPath();
        // 统一去掉末尾斜杠后精确匹配白名单
        String normalized = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (EXCLUDE_PATHS.contains(normalized)) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        // 提取 Token 并校验
        String token = extractToken(stripped, normalized);
        if (token == null) {
            return unauthorizedResponse(exchange, "未登录，请先登录");
        }

        try {
            // 解析 JWT，提取 userId 和 username 写入请求头，下游服务可直接获取
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // 只允许 access token 访问业务接口；refresh token 只能用于 /user/refresh 续期
            if (!"access".equals(claims.get("type", String.class))) {
                return unauthorizedResponse(exchange, "无效的凭证类型，请重新登录");
            }

            // 管理接口的角色闸门：/api/admin/** 仅 ADMIN 放行。
            // 与 X-User-Id 同一信任模型 —— 角色来自签发时固化的 claims，客户端伪造的头已在门口剥离
            if (normalized.startsWith(ADMIN_PATH_PREFIX)
                    && !JwtUtil.ROLE_ADMIN.equals(claims.get("role", String.class))) {
                return forbiddenResponse(exchange, "需要管理员权限");
            }

            // 令牌黑名单校验：登出/封禁的 token 立即失效；Redis 故障时降级放行（可用性优先）
            String blacklistKey = BLACKLIST_KEY_PREFIX
                    + DigestUtils.md5DigestAsHex(token.getBytes(StandardCharsets.UTF_8));
            return redisTemplate.hasKey(blacklistKey)
                    .flatMap(blacklisted -> Boolean.TRUE.equals(blacklisted)
                            ? unauthorizedResponse(exchange, "账号已退出登录，请重新登录")
                            : proceed(exchange, chain, stripped, claims))
                    .onErrorResume(e -> {
                        log.warn("Redis 黑名单检查失败，降级放行: {}", e.getMessage());
                        return proceed(exchange, chain, stripped, claims);
                    });
        } catch (Exception e) {
            log.warn("Token校验失败: {}", e.getMessage());
            return unauthorizedResponse(exchange, "Token无效或已过期，请重新登录");
        }
    }

    /** 验签通过：注入可信身份头后放行 */
    private Mono<Void> proceed(ServerWebExchange exchange, GatewayFilterChain chain,
                               ServerHttpRequest stripped, Claims claims) {
        String role = claims.get("role", String.class);
        ServerHttpRequest newRequest = stripped.mutate()
                .header("X-User-Id", String.valueOf(claims.get("userId", Long.class)))
                .header("X-Username", claims.get("username", String.class))
                .header("X-User-Role", role == null || role.isBlank() ? "USER" : role)
                .build();
        return chain.filter(exchange.mutate().request(newRequest).build());
    }

    /** 构造 403 无权限 JSON 响应（已登录但角色不足，与 401 未登录区分） */
    private Mono<Void> forbiddenResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        String body = "{\"code\":403,\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 从请求中提取 JWT Token：优先从 Authorization Header 获取；
     * URL 参数 ?token= 仅限 /api/ai 路径（EventSource 等无法设置请求头的场景），
     * 避免token进入访问日志/浏览器历史扩大泄露面
     */
    private String extractToken(ServerHttpRequest request, String path) {
        // 方式一：Authorization: Bearer <token>
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // 方式二：URL 参数 ?token=<token>（仅 AI 相关路径允许）
        if (path.startsWith(QUERY_TOKEN_PATH_PREFIX)) {
            String tokenParam = request.getQueryParams().getFirst("token");
            if (tokenParam != null && !tokenParam.isEmpty()) {
                return tokenParam;
            }
        }
        return null;
    }

    /**
     * 构造 401 未授权 JSON 响应
     */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        String body = "{\"code\":401,\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 过滤器优先级：值越小越先执行，-100 确保在大多数过滤器之前执行
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
