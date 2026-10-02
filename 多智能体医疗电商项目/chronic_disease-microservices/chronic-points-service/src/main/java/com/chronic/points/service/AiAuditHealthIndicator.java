package com.chronic.points.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * AI 代查审计的健康端点暴露（复核建议的可观测性补强）：
 * 把 {@link AiAuditRecorder} 的成功/失败计数放到 {@code /actuator/health} 的 details 里。
 *
 * <p>为什么是 HealthIndicator 而不是新接口：actuator 已在网关/运维的既有探活路径上，
 * 不需要为可观测性单独开内部端点；健康状态恒为 UP（审计失败不该把服务判死），
 * 计数只是 details —— "审计看起来在跑、其实一条没落"从 grep 日志变成看一眼数字。</p>
 *
 * @author chronic
 */
@Component
@RequiredArgsConstructor
public class AiAuditHealthIndicator implements HealthIndicator {

    private final AiAuditRecorder aiAuditRecorder;

    @Override
    public Health health() {
        return Health.up()
                .withDetail("aiAuditSuccess", aiAuditRecorder.getSuccessCount())
                .withDetail("aiAuditFailure", aiAuditRecorder.getFailureCount())
                .build();
    }
}
