package com.chronic.shop.controller;

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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 内部订单接口：供 AI 服务回答"我有哪些订单 / 花了多少 / 这笔什么状态 / 帮我取消"。
 *
 * <p>身份由 {@code X-Internal-Token} 证明（见 {@code InternalApiSecurityConfig}，fail-closed），
 * {@code userId} 只是"查谁的订单"的参数；服务端仍按 userId 校验订单归属，
 * 所以即便令牌泄露也不能读到别人（或不存在）的订单。</p>
 *
 * <p>查询条件（状态 / 药品名关键词 / 时间区间）全部下推到 SQL：
 * 早期实现是"取最近一页再在调用方过滤"，那样"我 3 月买过什么"只能在最近几笔里找，答案是错的。</p>
 *
 * @author chronic
 */
@Tag(name = "内部-订单")
@RestController
@RequestMapping("/internal/order")
@RequiredArgsConstructor
public class InternalOrderController {

    private final ShopOrderService shopOrderService;
    private final AdminAuditService adminAuditService;

    @Operation(summary = "按条件分页查询订单（内部）：状态 / 药品名关键词 / 下单时间区间；"
            + "status 支持查询别名 REFUNDED（= CANCELLED 且有退款金额）")
    @GetMapping("/list")
    public Result<Page<ShopOrder>> list(@RequestParam Long userId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String startTime,
                                        @RequestParam(required = false) String endTime,
                                        @RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<ShopOrder> page = shopOrderService.searchOrders(userId, status, keyword,
                parseStart(startTime), parseEnd(endTime), pageNum, pageSize);
        // AI 代用户查订单要留痕（谁在什么时候被查了什么条件）——查询虽不改数据，
        // 但能读全量订单明细，属于需要可追溯的敏感操作
        auditAiRead(userId, "AI_QUERY_ORDER_LIST",
                "status=" + status + ", keyword=" + keyword + ", start=" + startTime + ", end=" + endTime);
        return Result.success(page);
    }

    @Operation(summary = "订单汇总（内部）：笔数、各状态笔数、实付金额合计、积分消耗合计")
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary(@RequestParam Long userId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String startTime,
                                               @RequestParam(required = false) String endTime) {
        Map<String, Object> summary = shopOrderService.orderSummary(userId, status,
                parseStart(startTime), parseEnd(endTime));
        auditAiRead(userId, "AI_QUERY_ORDER_SUMMARY",
                "status=" + status + ", start=" + startTime + ", end=" + endTime);
        return Result.success(summary);
    }

    @Operation(summary = "按订单号查询订单（内部；仅限本人，查不到与无权限同样回'订单不存在'）")
    @GetMapping("/no/{orderNo}")
    public Result<ShopOrder> byOrderNo(@PathVariable String orderNo, @RequestParam Long userId) {
        ShopOrder order = shopOrderService.getOrderByNoForUser(orderNo, userId);
        auditAiRead(userId, "AI_QUERY_ORDER_DETAIL", "orderNo=" + orderNo);
        return Result.success(order);
    }

    /**
     * 取消订单（内部，供 AI 代用户发起）：复用用户取消链路 —— 归属校验、原子抢占取消、
     * 退库存/优惠券/积分/余额、秒杀单释放名额，全部与用户在页面上点"取消订单"一致。
     *
     * <p>AI 侧不直接调用：先由前端弹出确认，用户点确认才打这个接口（写操作不能让模型自作主张），
     * 并且写入审计表，标记为 AI 代操作。</p>
     */
    @Operation(summary = "取消订单（内部）：复用用户取消链路 + 写审计")
    @PostMapping("/cancel")
    public Result<Boolean> cancel(@RequestParam Long orderId, @RequestParam Long userId) {
        boolean ok = shopOrderService.cancelOrder(orderId, userId);
        adminAuditService.record(userId, "AI_CANCEL_ORDER", "ORDER", orderId,
                "operator=AI(assistant), ownerUserId=" + userId + ", result=" + ok);
        return Result.success(ok);
    }

    /** AI 代查留痕：operator 记为用户本人（是他在问），action 标明是 AI 发起的读取 */
    private void auditAiRead(Long userId, String action, String detail) {
        adminAuditService.record(userId, action, "USER_ASSET", userId,
                "operator=AI(assistant), " + detail);
    }

    /** 起始时间：接受 ISO 时间或纯日期（日期按当天 00:00:00） */
    private LocalDateTime parseStart(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseTime(value, true);
    }

    /** 结束时间：接受 ISO 时间或纯日期（日期按当天 23:59:59，否则"到今天"会把今天漏掉） */
    private LocalDateTime parseEnd(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseTime(value, false);
    }

    private LocalDateTime parseTime(String value, boolean startOfDay) {
        String text = value.trim();
        try {
            return LocalDateTime.parse(text);
        } catch (RuntimeException ignored) {
            // 继续尝试按纯日期解析
        }
        try {
            LocalDate date = LocalDate.parse(text);
            return startOfDay ? date.atStartOfDay() : date.atTime(23, 59, 59);
        } catch (RuntimeException e) {
            throw new BusinessException("时间格式不正确（应为 2026-09-01 或 2026-09-01T10:00:00）: " + value);
        }
    }
}
