package com.chronic.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理操作审计实体，对应 admin_audit 表（append-only：只增不改不删）
 *
 * @author chronic
 */
@Data
@TableName("admin_audit")
public class AdminAudit implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long operatorId;

    /** 动作，如 MEDICINE_UPDATE / SECKILL_CREATE / ORDER_REFUND / COMP_RETRY */
    private String action;

    /** 目标类型：MEDICINE / SECKILL_ACTIVITY / ORDER / COMPENSATION_TASK */
    private String targetType;

    private Long targetId;

    private String detail;

    private String traceId;

    private LocalDateTime createTime;
}
