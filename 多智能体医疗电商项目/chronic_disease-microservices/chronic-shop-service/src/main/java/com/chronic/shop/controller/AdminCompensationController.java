package com.chronic.shop.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.CompensationTask;
import com.chronic.shop.job.CompensationRetryJob;
import com.chronic.shop.mapper.CompensationTaskMapper;
import com.chronic.shop.service.AdminAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 补偿台账管理（管理端，/api/admin/** 由网关 ADMIN 角色闸门保护）：
 * 这是自动补偿闭环里"人"的那一环 —— PENDING/FAILED 任务可视、可即时手动重试。
 *
 * <p>与自动重试共用同一个 execute()：幂等（流水唯一键）由补偿语义本身保证，
 * 人工重试不会造成重复加/退款。</p>
 *
 * @author chronic
 */
@Tag(name = "管理-补偿台账")
@RestController
@RequestMapping("/admin/compensation")
@RequiredArgsConstructor
public class AdminCompensationController {

    private final CompensationTaskMapper compensationTaskMapper;
    private final CompensationRetryJob compensationRetryJob;
    private final AdminAuditService adminAuditService;

    @Operation(summary = "台账分页（可按状态过滤：PENDING/FAILED/DONE）")
    @GetMapping("/page")
    public Result<Page<CompensationTask>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize,
                                               @RequestParam(required = false) String status,
                                               @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        Page<CompensationTask> page = new Page<>(pageNum, Math.min(Math.max(pageSize, 1), 100));
        LambdaQueryWrapper<CompensationTask> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(CompensationTask::getStatus, status);
        }
        wrapper.orderByDesc(CompensationTask::getId);
        return Result.success(compensationTaskMapper.selectPage(page, wrapper));
    }

    @Operation(summary = "手动重试（即时执行一次，幂等由流水唯一键保证）")
    @PostMapping("/{id}/retry")
    public Result<Boolean> retry(@PathVariable Long id,
                                 @RequestHeader("X-User-Id") Long operatorId,
                                 @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireAdmin(role);
        CompensationTask task = compensationTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(404, "补偿任务不存在");
        }
        boolean success = compensationRetryJob.execute(task);
        adminAuditService.record(operatorId, "COMP_RETRY", "COMPENSATION_TASK", id,
                "bizType=" + task.getBizType() + ", success=" + success);
        return Result.success(success);
    }

    private void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }
}
