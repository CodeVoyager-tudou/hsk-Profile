package com.chronic.shop.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.service.AdminAuditService;
import com.chronic.shop.service.ShopOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 订单管理（管理端，/api/admin/** 由网关 ADMIN 角色闸门保护，写操作落审计）：
 * 全量订单查询 + 已支付订单代退款（复用用户取消的退款链路，幂等护栏一致）。
 *
 * @author chronic
 */
@Tag(name = "管理-订单")
@RestController
@RequestMapping("/admin/order")
@RequiredArgsConstructor
public class AdminOrderController {

    private final ShopOrderService shopOrderService;
    private final AdminAuditService adminAuditService;

    @Operation(summary = "全量订单分页（可按用户/状态过滤）")
    @GetMapping("/page")
    public Result<Page<ShopOrder>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "10") Integer pageSize,
                                        @RequestParam(required = false) Long userId,
                                        @RequestParam(required = false) String status,
                                        @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        Page<ShopOrder> page = new Page<>(pageNum, Math.min(Math.max(pageSize, 1), 100));
        LambdaQueryWrapper<ShopOrder> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(ShopOrder::getUserId, userId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(ShopOrder::getStatus, status);
        }
        wrapper.orderByDesc(ShopOrder::getId);
        Page<ShopOrder> result = shopOrderService.page(page, wrapper);
        // 管理端列表也显示药品图，与用户端口径一致
        shopOrderService.enrichMedicineImage(result.getRecords());
        return Result.success(result);
    }

    @Operation(summary = "全站订单统计（管理端）：总单数/今日单数/成交额/待支付/已取消/已退款")
    @GetMapping("/stats")
    public Result<java.util.Map<String, Object>> stats(
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        return Result.success(shopOrderService.adminStats());
    }

    @Operation(summary = "代退款（仅 PAID 订单；退余额/积分/库存/优惠券，幂等护栏与用户取消一致）")
    @PostMapping("/{id}/refund")
    public Result<Boolean> refund(@PathVariable Long id,
                                  @RequestHeader("X-User-Id") Long operatorId,
                                  @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        boolean result = shopOrderService.adminRefund(id);
        adminAuditService.record(operatorId, "ORDER_REFUND", "ORDER", id, "result=" + result);
        return Result.success(result);
    }

    private void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }
}
