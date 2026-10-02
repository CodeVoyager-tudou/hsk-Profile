package com.chronic.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chronic.shop.entity.Coupon;
import com.chronic.shop.entity.UserCoupon;

import java.util.List;

public interface CouponService extends IService<Coupon> {

    /**
     * 进行中的优惠券活动
     */
    List<Coupon> listActive();

    /**
     * 领取优惠券(校验活动窗口、总限量、每人限领)
     */
    UserCoupon receive(Long userId, Long couponId);

    /**
     * 查询用户持有的优惠券(附带券面信息)
     */
    List<com.chronic.shop.vo.UserCouponVO> listMyCoupons(Long userId, String status);

    /**
     * 核销(下单使用)
     */
    void markUsed(Long userCouponId, Long userId, Long orderId);

    /**
     * 退回(订单取消)
     *
     * @param userCouponId 用户券 ID
     * @param orderId      发起退回的订单 ID。
     *                     J-06 修复后必须传入：退回操作会校验「这张券确实由本单使用」
     *                     且「券仍未过期」，避免过期券被拉回可用状态形成折扣套利。
     */
    void markUnused(Long userCouponId, Long orderId);
}