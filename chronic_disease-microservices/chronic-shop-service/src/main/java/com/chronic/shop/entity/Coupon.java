package com.chronic.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券活动(模板)
 */
@Data
@TableName("coupon")
public class Coupon implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 券名称 */
    private String name;

    /** 类型: FULL_REDUCTION-满减 */
    private String type;

    /** 消费满 thresholdAmount 可用 */
    private BigDecimal thresholdAmount;

    /** 抵扣金额 */
    private BigDecimal discountAmount;

    /** 发放总量 */
    private Integer totalCount;

    /** 已发放数量 */
    private Integer issuedCount;

    /** 每人限领 */
    private Integer limitPerUser;

    /** 领取开始时间 */
    private LocalDateTime startTime;

    /** 领取结束时间(券有效期同止) */
    private LocalDateTime endTime;

    /** 状态: 0-下线 1-进行中 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
