package com.chronic.user.audit;

import com.chronic.common.config.TraceIdFilter;
import com.chronic.user.entity.HealthDataAudit;
import com.chronic.user.mapper.HealthDataAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 健康数据访问审计记录器（J-19 修复）。
 *
 * <h3>【为什么需要它】</h3>
 * {@code health_data_audit} 表在 V1 迁移里就建好了，注释写着"合规最小要求"，
 * 但**此前全代码库没有任何写入代码** —— 表恒为空。
 * 也就是说：任何一次健康档案读取（包括把它拼进 AI 提问上传）都没有留痕，
 * 一旦发生数据泄露<b>无法溯源</b>。本类把这个缺口补上。
 *
 * <h3>【记录什么】</h3>
 * <ul>
 *   <li>operatorId —— 谁发起的访问</li>
 *   <li>targetUserId —— 访问的是谁的数据</li>
 *   <li>action —— 动作类型（读档案 / 改档案…）</li>
 *   <li>clientIp —— 来源 IP（便于排查异常访问）</li>
 *   <li>traceId —— 与日志共用同一个链路 ID，可把一次请求在多个服务里的日志串起来</li>
 * </ul>
 *
 * <h3>【⚠️ 一处需要明确的设计取舍：审计失败时不阻断主流程】</h3>
 * 这里采用「尽力而为 + 失败打 ERROR 日志」：审计写库失败（表缺失、DB 抖动）
 * 时只记录错误，<b>不让健康建议接口整体失败</b>。
 * <p>更严格的合规姿态应当是 <b>fail-closed</b>（没有审计就不允许访问数据）。
 * 本项目选择可用性优先，是因为它同时承担"演示系统"的角色 ——
 * 若审计表不可用就整个功能不可用，演示会被一次 DB 抖动打断。
 * <b>真正上线做医疗合规时必须改成 fail-closed</b>，这一点在此显式记录，
 * 避免读者误以为当前实现已经满足严格合规要求。</p>
 *
 * @author chronic
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthDataAuditRecorder {

    /** 读取健康档案 */
    public static final String ACTION_READ_PROFILE = "READ_PROFILE";
    /** 写入/修改健康档案 */
    public static final String ACTION_WRITE_PROFILE = "WRITE_PROFILE";

    private final HealthDataAuditMapper healthDataAuditMapper;

    /**
     * 记录一次健康数据访问。
     *
     * @param operatorId   操作者（谁访问）
     * @param targetUserId 数据归属者（访问谁的数据）
     * @param action       动作，见本类常量
     * @param detail       补充说明，可为 null
     */
    public void record(Long operatorId, Long targetUserId, String action, String detail) {
        try {
            HealthDataAudit audit = new HealthDataAudit();
            audit.setOperatorId(operatorId);
            audit.setTargetUserId(targetUserId);
            audit.setAction(action);
            audit.setDetail(truncate(detail, 200));
            audit.setClientIp(currentClientIp());
            audit.setTraceId(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));
            healthDataAuditMapper.insert(audit);
        } catch (Exception e) {
            // 见类注释：可用性优先，审计失败不阻断主流程；但必须打 ERROR 让它可见，
            // 否则就又变成"静默没有审计"——那正是本次要修的问题。
            log.error("健康数据访问审计写入失败（访问未被记录）: operator={}, target={}, action={}",
                    operatorId, targetUserId, action, e);
        }
    }

    /**
     * 取当前请求的来源 IP。
     * <p>经过 nginx/网关时 {@code getRemoteAddr()} 拿到的是代理地址，
     * 因此优先取 {@code X-Forwarded-For} 的第一段（最靠近客户端的那个）。</p>
     * <p>没有请求上下文时（如定时任务、单元测试）返回 null，不抛错。</p>
     */
    private String currentClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // X-Forwarded-For 形如 "客户端IP, 代理1, 代理2"，取第一个
                return truncate(forwarded.split(",")[0].trim(), 64);
            }
            return truncate(request.getRemoteAddr(), 64);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
