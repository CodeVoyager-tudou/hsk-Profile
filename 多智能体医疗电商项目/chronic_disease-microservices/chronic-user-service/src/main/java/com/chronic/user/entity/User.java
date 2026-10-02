package com.chronic.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 系统用户实体，对应 sys_user 表
 * <p>
 * password 使用 WRITE_ONLY：接受请求传入（注册），但永不随响应序列化返回，
 * 防止 BCrypt 哈希经查询接口泄露被离线爆破。
 * </p>
 *
 * @author chronic
 */
@Data
@TableName("sys_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private String nickname;

    private String avatar;

    /** 角色：USER-普通用户 / ADMIN-管理员（登录时签发进 JWT，网关对 /api/admin/** 校验） */
    private String role;

    private Integer gender;

    private LocalDate birthday;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}