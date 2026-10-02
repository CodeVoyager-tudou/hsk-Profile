package com.chronic.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分流水记录实体，对应 points_record 表
 *
 * @author chronic
 */
@Data
@TableName("points_record")
public class PointsRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer points;

    private String type;

    private Long sourceId;

    private String remark;

    private LocalDateTime createTime;
}