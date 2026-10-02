package com.chronic.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chronic.common.dto.LoginDTO;
import com.chronic.common.exception.BusinessException;
import com.chronic.common.oss.OssStorageService;
import com.chronic.common.utils.JwtUtil;
import com.chronic.common.vo.LoginVO;
import com.chronic.user.entity.User;
import com.chronic.user.mapper.UserMapper;
import com.chronic.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现
 * <p>
 * 核心功能：注册（BCrypt 加密）、登录（JWT 签发；仅接受 BCrypt 密文）。
 * </p>
 *
 * @author chronic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /** BCrypt 密码编码器，用于密码加密和校验 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** JWT 签发工具（密钥来自 chronic.jwt.secret 配置，可用外部配置文件覆盖） */
    private final JwtUtil jwtUtil;

    /** 登录失败计数（Redis）；Redis 不可用时降级为不限制，保证登录不被拖垮 */
    private final StringRedisTemplate redisTemplate;

    /**
     * OSS 图片存储。Optional 注入：aliyun.oss 四要素没配齐时该 Bean 不存在（common 自动装配跳过），
     * 服务照常启动，头像上传接口会明确报"对象存储未启用" —— 与 RocketMQTemplate 的可选依赖同一套路。
     */
    private final Optional<OssStorageService> ossStorageService;

    private static final String LOGIN_FAIL_KEY_PREFIX = "chronic:login:fail:";

    /**
     * 令牌黑名单 key 前缀，必须与网关 {@code AuthFilter.BLACKLIST_KEY_PREFIX} 完全一致，
     * 否则服务端写入的黑名单网关读不到（J-17：refresh 接口在网关白名单内，
     * 它自己也要能查这个前缀）。
     */
    private static final String BLACKLIST_KEY_PREFIX = "jwt:bl:";

    /** 连续失败多少次锁定 */
    @Value("${chronic.security.login-max-failures:5}")
    private int loginMaxFailures = 5;

    /** 锁定时长（秒） */
    @Value("${chronic.security.login-lock-seconds:900}")
    private long loginLockSeconds = 900;

    /**
     * 根据用户名查询用户
     */
    @Override
    public User getByUsername(String username) {
        return getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    /**
     * 上传头像：文件存 OSS → 公开 URL 写回 sys_user.avatar → 删除旧头像对象。
     * <p>顺序是「先存新、再改库、最后删旧」：删旧失败只打日志不回滚 ——
     * 桶里多一张孤儿图是存储成本问题，用户头像丢数据是正确性问题，两者取轻。</p>
     */
    @Override
    public String updateAvatar(Long userId, MultipartFile file) {
        OssStorageService oss = ossStorageService
                .orElseThrow(() -> new BusinessException(503, "对象存储未启用，请配置 aliyun.oss 后重启"));
        String url = oss.upload("avatar/" + userId, file);
        User old = getById(userId);
        if (old == null) {
            // 网关只签发真实存在的 userId，走到这里说明库被动过：URL 已传 OSS 但没有归属人，直接报错
            throw new BusinessException(404, "用户不存在");
        }
        if (baseMapper.updateAvatar(userId, url) == 0) {
            throw new BusinessException(500, "头像更新失败");
        }
        if (old.getAvatar() != null && !old.getAvatar().isBlank()) {
            oss.deleteByUrl(old.getAvatar()); // deleteByUrl 内部校验 URL 是否属于本桶，删失败仅告警
        }
        return url;
    }

    /**
     * 用户注册：参数校验 → 用户名唯一性校验 → BCrypt 加密密码 → 保存用户
     */
    @Override
    public void register(User user) {
        if (user.getUsername() == null || user.getUsername().isBlank()
                || user.getPassword() == null || user.getPassword().isBlank()) {
            throw new BusinessException("用户名和密码不能为空");
        }
        User existUser = getByUsername(user.getUsername());
        if (existUser != null) {
            throw new BusinessException("用户名已存在");
        }
        // 防止 mass assignment：客户端传入的 id/状态/审计字段一律重置
        user.setId(null);
        user.setStatus(1);
        user.setRole("USER");
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        try {
            save(user);
        } catch (DuplicateKeyException e) {
            // 并发注册撞 username 唯一键：转为业务提示而非 500
            throw new BusinessException("用户名已存在");
        }
    }

    /**
     * 用户登录：校验账号状态 → BCrypt 密码比对 → 签发 JWT Token
     */
    @Override
    public LoginVO login(LoginDTO loginDTO) {
        String failKey = LOGIN_FAIL_KEY_PREFIX + loginDTO.getUsername();
        // 0. 防撞库：连续失败达到阈值后锁定一段时间（Redis 计数；Redis 异常降级为不限制）
        if (isLocked(failKey)) {
            throw new BusinessException(429, "登录失败次数过多，请 " + (loginLockSeconds / 60) + " 分钟后再试");
        }
        User user = getByUsername(loginDTO.getUsername());
        if (user == null) {
            recordLoginFailure(failKey);
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new BusinessException("账号已被禁用，请联系管理员");
        }

        String storedPassword = user.getPassword();
        // 仅接受 BCrypt 密文（$2a$/$2b$/$2y$）
        // 明文比对分支：既避免非常量时间比较，也防止非密文口令继续被接受。
        boolean isBcrypt = storedPassword != null
                && (storedPassword.startsWith("$2a$")
                    || storedPassword.startsWith("$2b$")
                    || storedPassword.startsWith("$2y$"));
        if (!isBcrypt || !passwordEncoder.matches(loginDTO.getPassword(), storedPassword)) {
            recordLoginFailure(failKey);
            throw new BusinessException("用户名或密码错误");
        }
        // 登录成功清零失败计数
        clearLoginFailure(failKey);

        // 登录成功，签发双令牌：短效 access（默认 2h）+ 长效 refresh（默认 7d）
        // role 签发进令牌：网关对 /api/admin/** 据此集中校验，下游不再重复判权
        String accessToken = jwtUtil.generateToken(user.getId(), user.getUsername(), JwtUtil.TYPE_ACCESS, user.getRole());
        String refreshToken = jwtUtil.generateToken(user.getId(), user.getUsername(), JwtUtil.TYPE_REFRESH, user.getRole());
        return new LoginVO(accessToken, refreshToken, user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    /** 是否已被锁定（读取 Redis 失败时视为未锁定，可用性优先） */
    private boolean isLocked(String failKey) {
        try {
            String value = redisTemplate.opsForValue().get(failKey);
            return value != null && Long.parseLong(value) >= loginMaxFailures;
        } catch (Exception e) {
            return false;
        }
    }

    /** 记录一次登录失败并顺带延长锁定期 */
    private void recordLoginFailure(String failKey) {
        try {
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(failKey, Duration.ofSeconds(loginLockSeconds));
            }
            if (count != null && count >= loginMaxFailures) {
                redisTemplate.expire(failKey, Duration.ofSeconds(loginLockSeconds));
            }
        } catch (Exception e) {
            // 计数失败不影响登录主流程（只损失防爆破增强）
        }
    }

    private void clearLoginFailure(String failKey) {
        try {
            redisTemplate.delete(failKey);
        } catch (Exception e) {
            // 忽略：下次登录成功会再清一次
        }
    }

    /**
     * 刷新令牌：校验 refresh 类型与账号状态后，签发**新的 access + 新的 refresh**。
     *
     * <p><b>J-17 修复（两处）</b>：</p>
     * <ol>
     *   <li><b>令牌轮换</b>：原实现把旧 refresh token 原样回传（7 天内可无限次续期），
     *       现在每次续期都签发新的一枚，并把用过的旧令牌立即拉黑 —— 旧令牌只能用一次。
     *       这样即使 refresh token 泄露，攻击者用过之后用户端的那枚就失效，
     *       而用户下次续期失败会重新登录，异常可被感知。</li>
     *   <li><b>黑名单校验</b>：{@code /api/user/refresh} 在网关白名单里（不经过 JWT 过滤器的
     *       黑名单检查），所以**必须在这里自己查一次**。否则登出时拉黑的 refresh token
     *       仍能换到新 access token，「登出」形同虚设。</li>
     * </ol>
     */
    @Override
    public LoginVO refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(401, "登录已过期，请重新登录");
        }
        io.jsonwebtoken.Claims claims;
        try {
            claims = jwtUtil.parseToken(refreshToken);
        } catch (Exception e) {
            throw new BusinessException(401, "登录已过期，请重新登录");
        }
        // 类型校验：access token 不能冒充 refresh token 换新令牌
        if (!JwtUtil.TYPE_REFRESH.equals(claims.get("type", String.class))) {
            throw new BusinessException(401, "无效的凭证类型");
        }
        // J-17：refresh 接口在网关白名单内，绕过了网关的黑名单检查，必须在这里补上
        if (isTokenBlacklisted(refreshToken)) {
            throw new BusinessException(401, "登录已过期，请重新登录");
        }
        User user = getById(claims.get("userId", Long.class));
        if (user == null) {
            throw new BusinessException(401, "账号不存在，请重新登录");
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new BusinessException(401, "账号已被禁用");
        }
        // J-17：轮换 —— 旧 refresh token 立刻作废（拉黑），同时签发新的一对令牌
        blacklistToken(refreshToken);
        String accessToken = jwtUtil.generateToken(user.getId(), user.getUsername(), JwtUtil.TYPE_ACCESS, user.getRole());
        String newRefreshToken = jwtUtil.generateToken(user.getId(), user.getUsername(), JwtUtil.TYPE_REFRESH, user.getRole());
        return new LoginVO(accessToken, newRefreshToken, user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    /**
     * 退出登录：access token 与 refresh token **双双**拉黑（J-17 修复）。
     *
     * <p>原实现只拉黑 access token，于是 refresh token 在登出后仍然可用，
     * 攻击者可以一直续期到它自然过期（默认 7 天）——「登出」并没有真正结束会话。</p>
     */
    @Override
    public void logout(String accessToken, String refreshToken) {
        blacklistToken(accessToken);
        blacklistToken(refreshToken);
    }

    /**
     * 把令牌写入黑名单，key 与网关 AuthFilter 保持一致（前缀 + token 的 md5），
     * TTL 取该令牌的剩余有效期（过期后自动清理，黑名单不会无限增长）。
     *
     * <p>任何解析/Redis 异常都只记日志不抛错：登出属"尽力而为"，
     * 前端本地凭证已经清掉，不应因为 Redis 抖动让登出接口报错。</p>
     */
    private void blacklistToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            long ttlMillis = jwtUtil.parseToken(token).getExpiration().getTime() - System.currentTimeMillis();
            if (ttlMillis > 0) {
                redisTemplate.opsForValue().set(
                        BLACKLIST_KEY_PREFIX + DigestUtils.md5DigestAsHex(token.getBytes(StandardCharsets.UTF_8)),
                        "1", ttlMillis, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            // 令牌本身已无效则无需拉黑；Redis 故障时降级（前端已清凭证）
            log.warn("令牌写入黑名单失败（不影响登出结果）: {}", e.getMessage());
        }
    }

    /** 该令牌是否已被拉黑；Redis 异常时返回 false（可用性优先，与网关降级策略一致） */
    private boolean isTokenBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            String key = BLACKLIST_KEY_PREFIX
                    + DigestUtils.md5DigestAsHex(token.getBytes(StandardCharsets.UTF_8));
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("黑名单检查失败，降级放行: {}", e.getMessage());
            return false;
        }
    }
}