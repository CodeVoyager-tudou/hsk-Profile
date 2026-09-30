package com.chronic.common.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 刷新令牌请求（用长效 refresh token 换取新的短效 access token）
 *
 * @author chronic
 */
@Data
public class RefreshTokenDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 登录时签发的 refresh token */
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
