package com.chronic.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 跨服务补偿台账：积分发放/回收/退还失败时落库，由定时任务重试并告警，
 * 替代原来"只打一行 error 日志、靠人看日志"的做法。
 */
@Data
@TableName("compensation_task")
public class CompensationTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型: ORDER_POINTS_GRANT / ORDER_POINTS_REVOKE / POINTS_REFUND */
    private String bizType;

    /** 业务ID（订单ID） */
    private Long bizId;

    private Long userId;

    /** 补偿参数(JSON)，如 {"points":56} */
    private String payload;

    /** PENDING-待补偿 DONE-已完成 FAILED-超过重试上限需人工 */
    private String status;

    private Integer retryCount;

    private String lastError;

    private LocalDateTime nextRetryTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
