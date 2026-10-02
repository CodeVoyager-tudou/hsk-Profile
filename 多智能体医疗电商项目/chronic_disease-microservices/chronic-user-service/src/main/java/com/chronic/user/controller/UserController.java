package com.chronic.user.controller;

import com.chronic.common.dto.LoginDTO;
import com.chronic.common.dto.RefreshTokenDTO;
import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.common.vo.LoginVO;
import com.chronic.user.entity.User;
import com.chronic.user.service.HealthAdviceService;
import com.chronic.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

/**
 * 用户管理 Controller
 * <p>
 * 提供用户注册、登录、查询等 REST API。
 * 登录接口免鉴权（Gateway AuthFilter 已放行），其他接口需携带 Token。
 * </p>
 *
 * @author chronic
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final HealthAdviceService healthAdviceService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        return Result.success(userService.login(loginDTO));
    }

    @Operation(summary = "退出登录（access + refresh 双双写入 Redis 黑名单，网关验黑后立即失效）")
    @PostMapping("/logout")
    public Result<Boolean> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody(required = false) RefreshTokenDTO body) {
        String accessToken = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        // J-17 修复：把 refresh token 也一起拉黑。
        // 原实现只拉黑 access token，而 refresh token 从未进黑名单、
        // 且 /api/user/refresh 在网关白名单内 —— 结果是「登出后仍可用 refresh token
        // 续期最长 7 天」，登出并没有真正结束会话。
        // 前端会在登出请求体里带上它；未携带时（旧客户端）退化为只拉黑 access token。
        String refreshToken = body == null ? null : body.getRefreshToken();
        userService.logout(accessToken, refreshToken);
        return Result.success(true);
    }

    @Operation(summary = "刷新令牌（refresh token 换新 access token，网关白名单放行）")
    @PostMapping("/refresh")
    public Result<LoginVO> refresh(@Valid @RequestBody RefreshTokenDTO dto) {
        return Result.success(userService.refresh(dto.getRefreshToken()));
    }

    @Operation(summary = "根据用户名查询（仅本人，防止枚举他人手机号等资料）")
    @GetMapping("/username/{username}")
    public Result<User> getByUsername(@PathVariable String username,
                                      @RequestHeader("X-User-Id") Long userId,
                                      @RequestHeader("X-Username") String currentUsername) {
        if (currentUsername == null || !currentUsername.equals(username)) {
            throw new BusinessException(403, "无权查看他人信息");
        }
        return Result.success(userService.getByUsername(username));
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<Void> register(@RequestBody User user) {
        userService.register(user);
        return Result.success();
    }

    @Operation(summary = "根据ID查询（仅本人，防止越权读取他人资料）")
    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id,
                                @RequestHeader("X-User-Id") Long currentUserId) {
        if (!id.equals(currentUserId)) {
            throw new BusinessException(403, "无权查看他人信息");
        }
        return Result.success(userService.getById(id));
    }

    @Operation(summary = "当前登录用户信息（脱敏，不含密码哈希）")
    @GetMapping("/info")
    public Result<User> info(@RequestHeader("X-User-Id") Long userId) {
        // User.password 标注 WRITE_ONLY，序列化时自动剔除
        return Result.success(userService.getById(userId));
    }

    @Operation(summary = "上传头像（multipart，存阿里云 OSS，返回公开 URL）")
    @PostMapping("/avatar")
    public Result<String> uploadAvatar(@RequestParam("file") MultipartFile file,
                                       @RequestHeader("X-User-Id") Long userId) {
        // 身份取自网关注入的 X-User-Id，路径/表单里不接收用户 ID —— 杜绝"帮别人换头像"的越权
        return Result.success(userService.updateAvatar(userId, file));
    }

    @Operation(summary = "AI 健康建议（WebClient 调用 Python 多智能体，失败自动降级通用建议）")
    @GetMapping("/health-advice")
    public Result<String> healthAdvice(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(healthAdviceService.getAdvice(userId));
    }
}