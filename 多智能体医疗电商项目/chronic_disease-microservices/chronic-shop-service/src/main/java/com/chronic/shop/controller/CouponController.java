package com.chronic.shop.controller;

import com.chronic.common.result.Result;
import com.chronic.shop.entity.Coupon;
import com.chronic.shop.entity.UserCoupon;
import com.chronic.shop.service.CouponService;
import com.chronic.shop.vo.UserCouponVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 优惠券 Controller，提供活动查询、领取、我的优惠券等 REST API
 *
 * @author chronic
 */
@Tag(name = "优惠券")
@RestController
@RequestMapping("/shop/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @Operation(summary = "进行中的优惠券活动")
    @GetMapping("/activity")
    public Result<List<Coupon>> listActive() {
        return Result.success(couponService.listActive());
    }

    @Operation(summary = "领取优惠券")
    @PostMapping("/receive")
    public Result<UserCoupon> receive(@RequestHeader("X-User-Id") Long userId,
                                      @RequestParam Long couponId) {
        return Result.success(couponService.receive(userId, couponId));
    }

    @Operation(summary = "我的优惠券（status 可选: UNUSED/USED/EXPIRED，path 中的 userId 必须与登录身份一致）")
    @GetMapping("/my/{userId}")
    public Result<List<UserCouponVO>> myCoupons(@PathVariable Long userId,
                                                @RequestHeader("X-User-Id") Long currentUserId,
                                                @RequestParam(required = false) String status) {
        if (!userId.equals(currentUserId)) {
            return Result.error(403, "无权查看他人优惠券");
        }
        return Result.success(couponService.listMyCoupons(userId, status));
    }
}