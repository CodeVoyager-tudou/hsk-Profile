package com.chronic.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 签到记录实体，对应 sign_in_record 表
 *
 * @author chronic
 */
@Data
@TableName("sign_in_record")
public class SignInRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate signDate;

    private Integer points;

    private Integer consecutiveDays;

    private LocalDateTime createTime;
}