package com.chronic.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户健康档案实体，对应 user_health_profile 表
 *
 * @author chronic
 */
@Data
@TableName("user_health_profile")
public class UserHealthProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private BigDecimal height;

    private BigDecimal weight;

    private String bloodType;

    private String allergies;

    private String medicalHistory;

    private String familyHistory;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}