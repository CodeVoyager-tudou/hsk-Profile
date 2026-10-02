package com.chronic.points.service;

import com.chronic.common.result.Result;
import com.chronic.points.feign.AuditFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 代查留痕（points 侧）：把"谁在什么时候被查了积分/余额"登记到 shop-service 的
 * {@code admin_audit} 表（该表在 edu_shop 库，本服务写不进去，故走内部接口）。
 *
 * <p>两条硬约束：</p>
 * <ol>
 *   <li><b>不阻断业务</b>：审计失败只告警，本次资产查询照常返回。审计是增强能力，
 *       让它把用户的正常查询拖挂是得不偿失的；但必须留下可排查的痕迹（WARN/ERROR）。</li>
 *   <li><b>不静默</b>：接口返回 data=false（对面写库失败）与调用抛异常都要分别告警——
 *       否则"审计看起来在跑，其实一条没落"这种状态无人发现。</li>
 * </ol>
 *
 * <p>可观测性（复核建议）：进程内维护成功/失败计数器（{@link AiAuditHealthIndicator}
 * 在 /actuator/health 里暴露）。日志会被滚动清走，计数器常驻——"审计到底有没有在落"
 * 从 grep 日志变成看一眼健康端点。</p>
 *
 * @author chronic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAuditRecorder {

    private final AuditFeignClient auditFeignClient;

    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    public long getSuccessCount() {
        return successCount.get();
    }

    public long getFailureCount() {
        return failureCount.get();
    }

    /**
     * 记录一次 AI 代查用户资产
     *
     * @param userId 被查的用户（是他在问，故 operator 也记为他）
     * @param field  查了什么：points / account
     */
    public void recordAssetQuery(Long userId, String field) {
        record(userId, "AI_QUERY_ASSET", "USER_ASSET", userId,
                "operator=AI(assistant), field=" + field);
    }

    /** 登记一条审计；任何失败都只告警不上抛（见类注释） */
    public void record(Long operatorId, String action, String targetType, Long targetId, String detail) {
        try {
            Result<Boolean> result = auditFeignClient.record(operatorId, action, targetType, targetId, detail);
            if (result == null || result.getCode() == null || result.getCode() != 200) {
                failureCount.incrementAndGet();
                log.warn("AI 查询审计登记失败（shop-service 返回非200）: action={}, detail={}", action, detail);
            } else if (!Boolean.TRUE.equals(result.getData())) {
                failureCount.incrementAndGet();
                log.warn("AI 查询审计未落库（对方写库失败，见 shop-service 日志）: action={}, detail={}",
                        action, detail);
            } else {
                successCount.incrementAndGet();
            }
        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.error("AI 查询审计登记异常（不影响本次查询）: action={}, detail={}", action, detail, e);
        }
    }
}
