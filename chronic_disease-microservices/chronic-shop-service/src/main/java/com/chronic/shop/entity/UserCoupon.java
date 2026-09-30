package com.chronic.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户持有的优惠券
 */
@Data
@TableName("user_coupon")
public class UserCoupon implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long couponId;

    private Long userId;

    /**
     * 该用户在该活动下的第几次领取(从 1 开始)
     * <p>
     * 与 user_id、coupon_id 共同构成业务唯一ID，对应唯一键 uk_user_coupon_user_coupon_no：
     * 并发、多实例、重试场景下由数据库兜底，保证既不超发也不超领
     */
    private Integer receiveNo;

    /** 状态: UNUSED-未使用 USED-已使用 EXPIRED-已过期 */
    private String status;

    private LocalDateTime receiveTime;

    private LocalDateTime expireTime;

    private LocalDateTime useTime;

    /** 核销本券的订单ID */
    private Long orderId;
}
