package com.chronic.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chronic.common.dto.LoginDTO;
import com.chronic.common.exception.BusinessException;
import com.chronic.common.oss.OssStorageService;
import com.chronic.common.utils.JwtUtil;
import com.chronic.common.vo.LoginVO;
import com.chronic.user.entity.User;
import com.chronic.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    /** 登录失败计数用；测试里未打桩 -> 辅助方法内部降级，不限制登录 */
    @Mock
    private StringRedisTemplate redisTemplate;

    /** 令牌黑名单的写入目标（J-17：登出/轮换都会写黑名单） */
    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserServiceImpl userService;

    private JwtUtil jwtUtil;

    private BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        // 真实 JwtUtil（固定测试密钥），保证 login/refresh 用例能拿到真实令牌；
        // baseMapper 由 ServiceImpl 基类字段注入，测试里用反射设置
        jwtUtil = new JwtUtil("testsecret0123456789abcdefghijklmnopqrstuvwxyz", 120, 7);
        userService = new UserServiceImpl(jwtUtil, redisTemplate, Optional.empty());
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
    }

    @Test
    void register_shouldSaveUserWithEncodedPassword() {
        User user = new User();
        user.setUsername("newuser");
        user.setPassword("123456");

        when(userMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenReturn(1);

        userService.register(user);

        // 密码应该是 BCrypt 加密后的值，不等于原始密码
        assertNotEquals("123456", user.getPassword());
        assertTrue(user.getPassword().startsWith("$2a$"));
        assertEquals(1, user.getStatus());
        verify(userMapper, times(1)).insert(any(User.class));
    }

    @Test
    void register_shouldThrow_whenUsernameExists() {
        User user = new User();
        user.setUsername("existing");
        user.setPassword("123456");

        User existing = new User();
        existing.setUsername("existing");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.register(user));
        assertEquals("用户名已存在", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void login_shouldReturnToken_whenPasswordCorrect() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword(passwordEncoder.encode("admin123"));
        user.setStatus(1);

        when(userMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(user);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("admin123");

        LoginVO vo = userService.login(dto);

        assertNotNull(vo.getToken());
        // 双令牌：登录必须同时签发 refresh token
        assertNotNull(vo.getRefreshToken());
        assertEquals(1L, vo.getUserId());
        assertEquals("admin", vo.getUsername());
    }

    @Test
    void refresh_shouldReturnNewAccessToken() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setStatus(1);
        when(userMapper.selectById(1L)).thenReturn(user);
        // 黑名单查询返回未拉黑
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String refreshToken = jwtUtil.generateToken(1L, "admin", JwtUtil.TYPE_REFRESH);
        LoginVO vo = userService.refresh(refreshToken);

        assertNotNull(vo.getToken());
        assertEquals(1L, vo.getUserId());
        // 新 access token 必须可解析且类型为 access
        assertEquals("access", jwtUtil.parseToken(vo.getToken()).get("type", String.class));
        // J-17 修复：refresh token 现在**会轮换** —— 每次续期签发新的一枚。
        // 原实现原样回传旧令牌（可反复使用 7 天），是"登出后仍能续期"的成因之一。
        assertNotNull(vo.getRefreshToken());
        assertNotEquals(refreshToken, vo.getRefreshToken(), "refresh token 必须轮换");
        assertEquals("refresh", jwtUtil.parseToken(vo.getRefreshToken()).get("type", String.class));
        // 用过的旧 refresh token 必须被立即拉黑
        verify(redisTemplate, times(1)).hasKey(anyString());
        verify(valueOperations, times(1)).set(anyString(), eq("1"), anyLong(), any(TimeUnit.class));
    }

    @Test
    void refresh_shouldReject_whenRefreshTokenBlacklisted() {
        // J-17 回归：refresh 接口在网关白名单内，绕过了网关的黑名单检查，
        // 因此必须在这里自己查 —— 否则登出拉黑的 refresh token 仍能换到新 access token。
        String refreshToken = jwtUtil.generateToken(1L, "admin", JwtUtil.TYPE_REFRESH);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.refresh(refreshToken));
        assertEquals(401, ex.getCode());
        // 已拉黑就不该再去查用户、更不该签发新令牌
        verify(userMapper, never()).selectById(anyLong());
    }

    @Test
    void logout_shouldBlacklistBothAccessAndRefresh() {
        // J-17 回归：登出必须把 access 与 refresh **双双**拉黑。
        // 原实现只拉黑 access token，导致 refresh token 在登出后仍可用最长 7 天。
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String accessToken = jwtUtil.generateToken(1L, "admin", JwtUtil.TYPE_ACCESS);
        String refreshToken = jwtUtil.generateToken(1L, "admin", JwtUtil.TYPE_REFRESH);

        userService.logout(accessToken, refreshToken);

        // 两次写入：access 一次、refresh 一次
        verify(valueOperations, times(2)).set(anyString(), eq("1"), anyLong(), any(TimeUnit.class));
    }

    @Test
    void logout_shouldNotFail_whenRefreshTokenAbsent() {
        // 旧客户端不带 refresh token 时退化为只拉黑 access token，不能报错
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String accessToken = jwtUtil.generateToken(1L, "admin", JwtUtil.TYPE_ACCESS);

        assertDoesNotThrow(() -> userService.logout(accessToken, null));
        verify(valueOperations, times(1)).set(anyString(), eq("1"), anyLong(), any(TimeUnit.class));
    }

    @Test
    void logout_shouldNotFail_whenRedisDown() {
        // Redis 故障时登出仍需成功（前端本地凭证已清），不能把异常抛给调用方
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        String accessToken = jwtUtil.generateToken(1L, "admin", JwtUtil.TYPE_ACCESS);

        assertDoesNotThrow(() -> userService.logout(accessToken, null));
    }

    @Test
    void refresh_shouldReject_accessTokenAsRefreshToken() {
        // 用 access token 冒充 refresh token 续期必须被拒绝
        String accessToken = jwtUtil.generateToken(1L, "admin", JwtUtil.TYPE_ACCESS);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.refresh(accessToken));
        assertEquals(401, ex.getCode());
    }

    @Test
    void refresh_shouldThrow_whenAccountDisabled() {
        User user = new User();
        user.setId(1L);
        user.setUsername("disabled");
        user.setStatus(0);
        when(userMapper.selectById(1L)).thenReturn(user);

        String refreshToken = jwtUtil.generateToken(1L, "disabled", JwtUtil.TYPE_REFRESH);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.refresh(refreshToken));
        assertEquals(401, ex.getCode());
    }

    @Test
    void refresh_shouldThrow_whenTokenInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.refresh("not-a-valid-token"));
        assertEquals(401, ex.getCode());
    }

    @Test
    void login_shouldThrow_whenUserNotFound() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(null);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("nobody");
        dto.setPassword("123");

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.login(dto));
        assertEquals("用户名或密码错误", ex.getMessage());
    }

    @Test
    void login_shouldThrow_whenAccountDisabled() {
        User user = new User();
        user.setId(1L);
        user.setUsername("disabled");
        user.setPassword("hashed");
        user.setStatus(0);

        when(userMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(user);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("disabled");
        dto.setPassword("123");

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.login(dto));
        assertEquals("账号已被禁用，请联系管理员", ex.getMessage());
    }

    @Test
    void login_shouldReject_whenStoredPasswordIsPlaintext() {
        User user = new User();
        user.setId(2L);
        user.setUsername("legacy");
        user.setPassword("plaintext123"); // 明文（非 BCrypt 密文）：必须拒绝，不再自愈升级
        user.setStatus(1);

        when(userMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(user);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("legacy");
        dto.setPassword("plaintext123");

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.login(dto));
        assertEquals("用户名或密码错误", ex.getMessage());
        // 明文比对分支已移除：即使"口令相同"也不放行，且不写库
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void login_shouldThrow_whenWrongPassword() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword(passwordEncoder.encode("correct"));
        user.setStatus(1);

        when(userMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(user);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("admin");
        dto.setPassword("wrong");

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.login(dto));
        assertEquals("用户名或密码错误", ex.getMessage());
    }

    @Test
    void updateAvatar_shouldUploadPersistAndDeleteOld() {
        OssStorageService oss = mock(OssStorageService.class);
        when(oss.upload(anyString(), any())).thenReturn("https://bucket/a.png");
        UserServiceImpl withOss = new UserServiceImpl(jwtUtil, redisTemplate, Optional.of(oss));
        ReflectionTestUtils.setField(withOss, "baseMapper", userMapper);

        User old = new User();
        old.setId(1L);
        old.setAvatar("https://bucket/old.png");
        when(userMapper.selectById(1L)).thenReturn(old);
        when(userMapper.updateAvatar(eq(1L), anyString())).thenReturn(1);

        MockMultipartFile file = new MockMultipartFile("file", "me.png", "image/png", new byte[]{1});
        String url = withOss.updateAvatar(1L, file);

        assertEquals("https://bucket/a.png", url);
        verify(userMapper).updateAvatar(eq(1L), eq("https://bucket/a.png"));
        // 旧头像属于本桶 → 会被清理
        verify(oss).deleteByUrl("https://bucket/old.png");
    }

    @Test
    void updateAvatar_shouldThrow503_whenOssNotConfigured() {
        // setUp 里就是 Optional.empty()：OSS 四要素未配齐时接口明确报"未启用"，而不是 NPE
        MockMultipartFile file = new MockMultipartFile("file", "me.png", "image/png", new byte[]{1});
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateAvatar(1L, file));
        assertTrue(ex.getMessage().contains("对象存储未启用"));
    }
}
