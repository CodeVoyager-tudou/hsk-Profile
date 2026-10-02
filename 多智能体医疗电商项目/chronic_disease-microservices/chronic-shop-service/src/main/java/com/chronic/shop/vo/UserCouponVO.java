package com.chronic.shop.vo;

import com.chronic.shop.entity.Coupon;
import com.chronic.shop.entity.UserCoupon;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户优惠券视图(带券面信息)
 */
@Data
public class UserCouponVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long couponId;

    private Long userId;

    /** UNUSED-未使用 USED-已使用 EXPIRED-已过期 */
    private String status;

    private String couponName;

    /** 满 X 可用 */
    private BigDecimal thresholdAmount;

    /** 减 Y */
    private BigDecimal discountAmount;

    private LocalDateTime receiveTime;

    private LocalDateTime expireTime;

    private LocalDateTime useTime;

    private Long orderId;

    public static UserCouponVO of(UserCoupon userCoupon, Coupon coupon) {
        UserCouponVO vo = new UserCouponVO();
        vo.setId(userCoupon.getId());
        vo.setCouponId(userCoupon.getCouponId());
        vo.setUserId(userCoupon.getUserId());
        vo.setStatus(userCoupon.getStatus());
        vo.setReceiveTime(userCoupon.getReceiveTime());
        vo.setExpireTime(userCoupon.getExpireTime());
        vo.setUseTime(userCoupon.getUseTime());
        vo.setOrderId(userCoupon.getOrderId());
        if (coupon != null) {
            vo.setCouponName(coupon.getName());
            vo.setThresholdAmount(coupon.getThresholdAmount());
            vo.setDiscountAmount(coupon.getDiscountAmount());
        }
        return vo;
    }
}
