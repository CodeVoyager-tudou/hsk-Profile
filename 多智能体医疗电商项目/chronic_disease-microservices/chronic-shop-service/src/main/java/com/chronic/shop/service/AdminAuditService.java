package com.chronic.shop.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chronic.shop.entity.AdminAudit;
import org.slf4j.MDC;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 管理操作审计服务：所有 /admin/** 的写操作都必须落一条审计。
 *
 * <p>traceId 取自 MDC（TraceIdFilter 已把网关生成/透传的 X-Trace-Id 写入），
 * 事后可按同一 ID 串联网关、本服务与下游日志，完整回放一次管理操作的上下文。
 * 审计写入失败只告警不回滚业务 —— 审计是增强，不能反过来阻断管理动作；
 * 但失败会打 ERROR 日志留痕。</p>
 *
 * @author chronic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final com.chronic.shop.mapper.AdminAuditMapper adminAuditMapper;

    public void record(Long operatorId, String action, String targetType, Long targetId, String detail) {
        tryRecord(operatorId, action, targetType, targetId, detail);
    }

    /**
     * 写审计并返回是否真的落下：调用方（服务间审计）靠返回值判断要不要告警。
     *
     * <p>与 {@link #record} 同样是"失败不抛异常"——审计是增强，不能反过来阻断业务。</p>
     */
    public boolean tryRecord(Long operatorId, String action, String targetType, Long targetId, String detail) {
        AdminAudit audit = new AdminAudit();
        audit.setOperatorId(operatorId);
        audit.setAction(action);
        audit.setTargetType(targetType);
        audit.setTargetId(targetId);
        audit.setDetail(truncate(detail, 500));
        audit.setTraceId(MDC.get(com.chronic.common.config.TraceIdFilter.TRACE_ID_MDC_KEY));
        try {
            adminAuditMapper.insert(audit);
            return true;
        } catch (Exception e) {
            log.error("管理审计写入失败（需人工排查）: operator={}, action={}, target={}/{}, detail={}",
                    operatorId, action, targetType, targetId, detail, e);
            return false;
        }
    }

    /**
     * 截断审计明细（复核 P2-3 顺手项）：{@code String.substring} 可能把代理对（emoji 等）
     * 从中间切断，产生非法 UTF-16 序列，写入 utf8mb4 列时插入失败 → 审计静默丢失。
     * 截断点落在高代理项上时回退一位，保证边界完整。
     */
    private String truncate(String value, int maxUnits) {
        if (value == null || value.length() <= maxUnits) {
            return value;
        }
        int end = maxUnits;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /** 审计分页（管理端"谁在什么时候改了什么"） */
    public Page<AdminAudit> page(long pageNum, long pageSize) {
        return adminAuditMapper.selectPage(
                new Page<>(pageNum, Math.min(Math.max(pageSize, 1), 100)),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AdminAudit>()
                        .orderByDesc(AdminAudit::getId));
    }
}
