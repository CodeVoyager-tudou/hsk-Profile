package com.chronic.shop.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.entity.SeckillActivity;
import com.chronic.shop.mapper.MedicineMapper;
import com.chronic.shop.mapper.SeckillActivityMapper;
import com.chronic.shop.service.AdminAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动管理（管理端，/api/admin/** 由网关 ADMIN 角色闸门保护，写操作落审计）。
 * 取代"改种子数据"的原始手段，闭合秒杀活动无入口的遗留。
 *
 * @author chronic
 */
@Tag(name = "管理-秒杀")
@RestController
@RequestMapping("/admin/seckill")
@RequiredArgsConstructor
public class AdminSeckillController {

    private final SeckillActivityMapper seckillActivityMapper;
    private final MedicineMapper medicineMapper;
    private final AdminAuditService adminAuditService;
    private final com.chronic.shop.service.SeckillService seckillService;

    @Operation(summary = "活动分页（管理视角：含未开始/已结束/下架）")
    @GetMapping("/page")
    public Result<Page<SeckillActivity>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "10") Integer pageSize,
                                              @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        return Result.success(seckillActivityMapper.selectPage(
                new Page<>(pageNum, Math.min(Math.max(pageSize, 1), 100)),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillActivity>()
                        .orderByDesc(SeckillActivity::getId)));
    }

    @Operation(summary = "创建秒杀活动")
    @PostMapping("/create")
    public Result<SeckillActivity> create(@RequestParam Long medicineId,
                                          @RequestParam String title,
                                          @RequestParam BigDecimal seckillPrice,
                                          @RequestParam Integer totalStock,
                                          @RequestParam String startTime,
                                          @RequestParam String endTime,
                                          @RequestHeader("X-User-Id") Long operatorId,
                                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        Medicine medicine = medicineMapper.selectById(medicineId);
        if (medicine == null) {
            throw new BusinessException(404, "药品不存在");
        }
        if (seckillPrice == null || seckillPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("秒杀价必须大于 0");
        }
        if (totalStock == null || totalStock <= 0) {
            throw new BusinessException("总名额必须大于 0");
        }
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        if (!start.isBefore(end)) {
            throw new BusinessException("开始时间必须早于结束时间");
        }
        SeckillActivity activity = new SeckillActivity();
        activity.setMedicineId(medicineId);
        activity.setTitle(title);
        activity.setSeckillPrice(seckillPrice);
        activity.setTotalStock(totalStock);
        activity.setSoldCount(0);
        activity.setStartTime(start);
        activity.setEndTime(end);
        activity.setStatus(1);
        seckillActivityMapper.insert(activity);
        adminAuditService.record(operatorId, "SECKILL_CREATE", "SECKILL_ACTIVITY", activity.getId(),
                "title=" + title + ", price=" + seckillPrice + ", stock=" + totalStock);
        return Result.success(activity);
    }

    @Operation(summary = "活动上下架（1-上架 0-下架）")
    @PostMapping("/{id}/status")
    public Result<Boolean> updateStatus(@PathVariable Long id,
                                        @RequestParam Integer status,
                                        @RequestHeader("X-User-Id") Long operatorId,
                                        @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException("status 仅允许 0/1");
        }
        if (seckillActivityMapper.updateStatus(id, status) == 0) {
            throw new BusinessException(404, "活动不存在");
        }
        seckillService.evictActivityCache(id);
        adminAuditService.record(operatorId, "SECKILL_STATUS", "SECKILL_ACTIVITY", id, "status=" + status);
        return Result.success(true);
    }

    @Operation(summary = "调整活动时间窗口（活动缓存立即失效；名额由预热任务下一轮刷新）")
    @PostMapping("/{id}/time")
    public Result<Boolean> updateTime(@PathVariable Long id,
                                      @RequestParam String startTime,
                                      @RequestParam String endTime,
                                      @RequestHeader("X-User-Id") Long operatorId,
                                      @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        if (!start.isBefore(end)) {
            throw new BusinessException("开始时间必须早于结束时间");
        }
        if (seckillActivityMapper.updateTime(id, start, end) == 0) {
            throw new BusinessException(404, "活动不存在（或时间参数非法）");
        }
        seckillService.evictActivityCache(id);
        adminAuditService.record(operatorId, "SECKILL_TIME", "SECKILL_ACTIVITY", id,
                "start=" + start + ", end=" + end);
        return Result.success(true);
    }

    @Operation(summary = "名额校正（对账偏差人工恢复：把指定用户的名额回补 Redis 并移出已购名单）")
    @PostMapping("/{id}/restock")
    public Result<Boolean> restock(@PathVariable Long id,
                                   @RequestParam Long userId,
                                   @RequestHeader("X-User-Id") Long operatorId,
                                   @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        if (seckillActivityMapper.selectById(id) == null) {
            throw new BusinessException(404, "活动不存在");
        }
        seckillService.restock(id, userId);
        adminAuditService.record(operatorId, "SECKILL_RESTOCK", "SECKILL_ACTIVITY", id,
                "restock userId=" + userId);
        return Result.success(true);
    }

    private void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }
}
