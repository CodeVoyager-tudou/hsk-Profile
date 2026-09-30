package com.chronic.shop.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.AdminAudit;
import com.chronic.shop.service.AdminAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 审计查询（管理端，/api/admin/** 由网关 ADMIN 角色闸门保护）：
 * "谁在什么时候对什么做了什么"——管理操作全生命周期的最后一环。
 *
 * @author chronic
 */
@Tag(name = "管理-操作审计")
@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminAuditService adminAuditService;

    @Operation(summary = "管理操作审计分页（按时间倒序，append-only 只读）")
    @GetMapping("/page")
    public Result<Page<AdminAudit>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "10") Integer pageSize,
                                         @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!"ADMIN".equals(role)) {
            throw new BusinessException(403, "需要管理员权限");
        }
        return Result.success(adminAuditService.page(
                pageNum == null ? 1 : pageNum, pageSize == null ? 10 : pageSize));
    }
}
