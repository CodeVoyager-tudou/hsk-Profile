package com.chronic.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 健康数据访问审计（J-19 修复）。
 *
 * <h3>【小白先看：这张表是干什么的】</h3>
 * 健康档案（身高体重、过敏史、既往病史、家族病史）属于**敏感个人信息**。
 * 合规上要求能回答一个问题：<b>「谁，在什么时候，看了/改了谁的健康信息？」</b>
 * 万一发生数据泄露，只有留下这个记录才可能溯源。
 *
 * <h3>【修复前的问题】</h3>
 * 这张表和它的注释在 V1 迁移里就定义好了，enums（READ_PROFILE/WRITE_PROFILE/…）
 * 也写清楚了，但<b>全代码库没有任何一行写入代码</b> —— 表永远是空的，
 * "合规最小要求"只是一句声明。这属于「声称有能力、实际没有」，
 * 比单纯少一个功能更危险：读代码的人会以为审计已经做好了。
 *
 * <p>本类即该记录的数据载体，由 {@code HealthDataAuditRecorder} 负责写入。</p>
 *
 * @author chronic
 */
@Data
@TableName("health_data_audit")
public class HealthDataAudit implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者用户ID（谁发起的访问） */
    private Long operatorId;

    /** 被访问数据的归属用户ID（访问的是谁的数据） */
    private Long targetUserId;

    /** 动作：READ_PROFILE / WRITE_PROFILE / DELETE_AI_SESSION 等 */
    private String action;

    /** 补充说明（例如"AI 健康建议"） */
    private String detail;

    /** 来源 IP（便于异常访问排查） */
    private String clientIp;

    /** 全链路 traceId，可与日志串起同一次请求 */
    private String traceId;

    private LocalDateTime createTime;
}
