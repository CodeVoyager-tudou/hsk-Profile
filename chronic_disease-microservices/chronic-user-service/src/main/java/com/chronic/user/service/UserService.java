package com.chronic.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chronic.common.dto.LoginDTO;
import com.chronic.common.vo.LoginVO;
import com.chronic.user.entity.User;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户服务接口
 *
 * @author chronic
 */
public interface UserService extends IService<User> {

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户信息，不存在返回 null
     */
    User getByUsername(String username);

    /**
     * 用户注册（密码 BCrypt 加密后存储）
     *
     * @param user 用户信息（含明文密码）
     */
    void register(User user);

    /**
     * 用户登录（校验密码，返回 access + refresh 双令牌）
     *
     * @param loginDTO 登录请求（用户名 + 密码）
     * @return 登录结果（双令牌 + 用户信息）
     */
    LoginVO login(LoginDTO loginDTO);

    /**
     * 用长效 refresh token 换取新的短效 access token（校验令牌类型与账号状态）。
     *
     * <p><b>J-17 修复：refresh token 现在会「轮换」</b>（每次续期签发新的一枚，
     * 并把用过的旧令牌拉黑）。原实现是原样回传旧 refresh token，导致
     * 「登出后 refresh token 仍然有效」——因为登出只拉黑了 access token，
     * 而 refresh token 从未进过黑名单，攻击者可继续续期最长 7 天。</p>
     *
     * @param refreshToken 登录时签发的 refresh token（用过即失效）
     * @return 新的 access token + **新的 refresh token**（前端必须保存新的那枚）
     */
    LoginVO refresh(String refreshToken);

    /**
     * 退出登录：把 access token 与 refresh token **双双**写入黑名单。
     *
     * <p>J-17 修复：原实现只拉黑 access token（且逻辑写在 Controller 里），
     * 于是 refresh token 在登出后仍然可用，等于登出没有真正结束会话。
     * 现在两者一起拉黑，TTL 取各自剩余有效期。</p>
     *
     * @param accessToken  当前请求头里的 access token（可为空）
     * @param refreshToken 前端持有的 refresh token（可为空；为空时只拉黑 access）
     */
    void logout(String accessToken, String refreshToken);

    /**
     * 上传头像：文件存阿里云 OSS，公开 URL 写回 sys_user.avatar。
     * OSS 未配置（aliyun.oss 四要素不齐）时抛业务异常"对象存储未启用"，不影响其他功能。
     *
     * @param userId 当前登录用户（网关注入的可信身份，非路径参数）
     * @param file   multipart 图片文件
     * @return 图片的公开访问 URL
     */
    String updateAvatar(Long userId, MultipartFile file);
}