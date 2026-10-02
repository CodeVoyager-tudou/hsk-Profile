package com.chronic.shop.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 购物车结算请求：勾选的购物车条目 + 可选优惠券 + 支付方式。
 *
 * <p>结算范围由前端勾选决定（itemIds），而不是"整个购物车"——
 * 用户可能只想先买其中两件。身份不放在请求体里，一律取网关注入的 X-User-Id，
 * 购物车条目归属在 Service 里再校验一遍（防越权拿别人的条目结算）。</p>
 */
@Data
public class CartCheckoutRequest {

    /** 勾选的购物车条目 id（cart_item.id）列表 */
    @NotEmpty(message = "请先勾选要结算的商品")
    private List<Long> itemIds;

    /** 使用的用户优惠券 id（user_coupon.id），不传 = 不用券 */
    private Long userCouponId;

    /** 支付方式: CASH-现金(默认) BALANCE-余额 */
    private String payType;
}
