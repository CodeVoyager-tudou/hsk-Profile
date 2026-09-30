package com.chronic.user.audit;

import com.chronic.user.entity.HealthDataAudit;
import com.chronic.user.mapper.HealthDataAuditMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 健康数据访问审计的回归测试（J-19）。
 *
 * <p>缺陷背景：{@code health_data_audit} 表在 V1 里就建好并注明"合规最小要求"，
 * 但**全代码库没有任何写入代码**，表恒为空 —— 健康档案被读取（且内容会传给 AI）
 * 却不留任何痕迹，出事无法溯源。本测试锁定"必须写入审计"这一行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class HealthDataAuditRecorderTest {

    @Mock
    private HealthDataAuditMapper healthDataAuditMapper;

    @AfterEach
    void tearDown() {
        // 清掉请求上下文与 MDC，避免用例之间互相影响
        RequestContextHolder.resetRequestAttributes();
        MDC.clear();
    }

    private HealthDataAuditRecorder recorder() {
        return new HealthDataAuditRecorder(healthDataAuditMapper);
    }

    @Test
    void record_shouldInsertAuditRowWithAllFields() {
        MDC.put("traceId", "trace-123");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder().record(7L, 8L, HealthDataAuditRecorder.ACTION_READ_PROFILE, "AI 健康建议");

        ArgumentCaptor<HealthDataAudit> captor = ArgumentCaptor.forClass(HealthDataAudit.class);
        verify(healthDataAuditMapper, times(1)).insert(captor.capture());
        HealthDataAudit audit = captor.getValue();
        assertEquals(7L, audit.getOperatorId(), "操作者应为发起访问的人");
        assertEquals(8L, audit.getTargetUserId(), "目标应为档案归属者");
        assertEquals(HealthDataAuditRecorder.ACTION_READ_PROFILE, audit.getAction());
        assertEquals("AI 健康建议", audit.getDetail());
        assertEquals("10.0.0.9", audit.getClientIp());
        assertEquals("trace-123", audit.getTraceId(), "traceId 应取自 MDC，便于与日志串联");
    }

    @Test
    void record_shouldPreferForwardedForOverRemoteAddr() {
        // 经过 nginx/网关时 getRemoteAddr 是代理地址，必须优先用 X-Forwarded-For
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.16.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 172.16.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        recorder().record(1L, 1L, HealthDataAuditRecorder.ACTION_READ_PROFILE, null);

        ArgumentCaptor<HealthDataAudit> captor = ArgumentCaptor.forClass(HealthDataAudit.class);
        verify(healthDataAuditMapper).insert(captor.capture());
        assertEquals("203.0.113.5", captor.getValue().getClientIp(), "应取 XFF 的第一段（最靠近客户端）");
    }

    @Test
    void record_shouldNotFail_whenNoRequestContext() {
        // 定时任务 / 单元测试等无请求上下文的场景：IP 记 null，但不能抛错
        assertDoesNotThrow(() ->
                recorder().record(1L, 1L, HealthDataAuditRecorder.ACTION_READ_PROFILE, null));

        ArgumentCaptor<HealthDataAudit> captor = ArgumentCaptor.forClass(HealthDataAudit.class);
        verify(healthDataAuditMapper).insert(captor.capture());
        assertNull(captor.getValue().getClientIp());
    }

    @Test
    void record_shouldTruncateOverlongDetail() {
        String longDetail = "x".repeat(500);

        recorder().record(1L, 1L, HealthDataAuditRecorder.ACTION_READ_PROFILE, longDetail);

        ArgumentCaptor<HealthDataAudit> captor = ArgumentCaptor.forClass(HealthDataAudit.class);
        verify(healthDataAuditMapper).insert(captor.capture());
        assertEquals(200, captor.getValue().getDetail().length(), "detail 列宽 200，必须截断");
    }

    @Test
    void record_shouldNotBreakMainFlow_whenInsertFails() {
        // 明确的取舍（见类注释）：审计写库失败时"可用性优先"，不阻断健康建议接口。
        // 但必须打 ERROR 让问题可见，绝不能静默 —— 静默就等于又回到"没有审计"。
        when(healthDataAuditMapper.insert(any(HealthDataAudit.class)))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() ->
                recorder().record(1L, 1L, HealthDataAuditRecorder.ACTION_READ_PROFILE, "d"));
        verify(healthDataAuditMapper, times(1)).insert(any(HealthDataAudit.class));
    }
}
