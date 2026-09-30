package com.chronic.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户积分实体，对应 user_points 表（@Version 乐观锁防并发）
 *
 * @author chronic
 */
@Data
@TableName("user_points")
public class UserPoints implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer totalPoints;

    private Integer usedPoints;

    /** 连续签到天数（昨天未签到则由定时任务归零，见 PointsJobHandler） */
    private Integer consecutiveDays;

    /** 最近一次签到日期（断签判定依据） */
    private LocalDate lastSignDate;

    @Version
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}