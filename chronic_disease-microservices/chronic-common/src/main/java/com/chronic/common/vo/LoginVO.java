package com.chronic.common.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 登录响应结果
 *
 * @author chronic
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** JWT access token，短效（默认 2 小时），每次请求携带 */
    private String token;

    /** JWT refresh token，长效（默认 7 天），仅在调 /user/refresh 续期时使用 */
    private String refreshToken;

    /** 用户ID */
    private Long userId;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 角色：USER-普通用户 / ADMIN-管理员（前端据此显示管理入口） */
    private String role;
}