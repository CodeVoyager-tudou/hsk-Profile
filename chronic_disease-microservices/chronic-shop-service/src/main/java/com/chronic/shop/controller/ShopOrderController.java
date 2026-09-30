package com.chronic.shop.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.pay.OrderPaymentService;
import com.chronic.shop.service.ShopOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 订单管理 Controller，提供下单、兑换、取消、查询、支付回调等 REST API
 * <p>
 * 用户身份一律取自网关鉴权后注入的 X-User-Id 请求头（JWT 解析结果），
 * 前端传入的 userId 参数仅作兼容保留、不作为身份依据，杜绝越权（IDOR）。
 * </p>
 *
 * @author chronic
 */
@Tag(name = "订单管理")
@RestController
@RequestMapping("/shop/order")
@RequiredArgsConstructor
public class ShopOrderController {

    private final ShopOrderService shopOrderService;
    private final OrderPaymentService orderPaymentService;

    /** 服务间共享密钥：支付回调属内部接口，须携带 X-Internal-Token */
    @Value("${chronic.internal-token}")
    private String internalToken;

    @Operation(summary = "创建订单（购买药品，可携带优惠券；X-Request-Id 幂等防重；payType=CASH现金/BALANCE余额）")
    @PostMapping("/create")
    public Result<ShopOrder> createOrder(@RequestHeader("X-User-Id") Long userId,
                                         @RequestParam Long medicineId,
                                         @RequestParam Integer quantity,
                                         @RequestParam(required = false) Long userCouponId,
                                         @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                         @RequestParam(defaultValue = "CASH") String payType) {
        return Result.success(shopOrderService.createOrder(userId, medicineId, quantity, userCouponId,
                requestId, payType));
    }

    @Operation(summary = "积分兑换药品")
    @PostMapping("/exchange")
    public Result<ShopOrder> exchangeOrder(@RequestHeader("X-User-Id") Long userId,
                                           @RequestParam Long medicineId,
                                           @RequestParam Integer quantity) {
        return Result.success(shopOrderService.exchangeOrder(userId, medicineId, quantity));
    }

    @Operation(summary = "继续支付待支付订单（PENDING 现金单走收银台确认推进 PAID；余额单为同步支付无 PENDING 态）")
    @PostMapping("/pay/{orderId}")
    public Result<ShopOrder> payPendingOrder(@PathVariable Long orderId,
                                             @RequestHeader("X-User-Id") Long userId) {
        return Result.success(shopOrderService.payPendingOrder(orderId, userId));
    }

    @Operation(summary = "取消订单")
    @PostMapping("/cancel/{orderId}")
    public Result<Boolean> cancelOrder(@PathVariable Long orderId,
                                       @RequestHeader("X-User-Id") Long userId) {
        return Result.success(shopOrderService.cancelOrder(orderId, userId));
    }

    @Operation(summary = "模拟支付回调（PENDING -> PAID）：内部令牌校验 + 幂等，重复回调只会生效一次")
    @PostMapping("/pay/callback")
    public Result<ShopOrder> payCallback(@RequestParam Long orderId,
                                         @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!matchesInternalToken(token)) {
            return Result.error(403, "非法回调请求");
        }
        return Result.success(orderPaymentService.confirmPaid(orderId));
    }

    @Operation(summary = "订单详情（仅本人可见）")
    @GetMapping("/{orderId}")
    public Result<ShopOrder> getById(@PathVariable Long orderId,
                                     @RequestHeader("X-User-Id") Long userId) {
        return Result.success(shopOrderService.getOrderForUser(orderId, userId));
    }

    @Operation(summary = "用户订单列表（仅本人，path 中的 userId 必须与登录身份一致）")
    @GetMapping("/list/{userId}")
    public Result<Page<ShopOrder>> listByUserId(@PathVariable Long userId,
                                                @RequestHeader("X-User-Id") Long currentUserId,
                                                @RequestParam(defaultValue = "1") Integer pageNum,
                                                @RequestParam(defaultValue = "10") Integer pageSize) {
        if (!userId.equals(currentUserId)) {
            return Result.error(403, "无权查看他人订单");
        }
        return Result.success(shopOrderService.listByUser(userId, pageNum, pageSize));
    }

    /** 常量时间比较，避免令牌逐字符比较被计时侧信道爆破 */
    private boolean matchesInternalToken(String token) {
        if (token == null || internalToken == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8));
    }
}
