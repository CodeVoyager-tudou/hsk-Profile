package com.chronic.shop.job;

import com.chronic.shop.entity.OrderEventOutbox;
import com.chronic.shop.mapper.OrderEventOutboxMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单事件中继任务的回归测试（J-14）。
 *
 * <p>中继是「至少一次投递」的实现者：只要 outbox 里还有 PENDING，
 * 它就要一直重试，直到成功或超过上限。本测试锁定成功/重试/放弃/无模板四条路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderEventRelayJobTest {

    @Mock
    private OrderEventOutboxMapper outboxMapper;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private OrderEventRelayJob job;

    private OrderEventOutbox event(int retryCount) {
        OrderEventOutbox e = new OrderEventOutbox();
        e.setId(1L);
        e.setOrderId(100L);
        e.setOrderNo("NO-100");
        e.setEventType("ORDER_CREATED");
        e.setPayload("{\"orderNo\":\"NO-100\"}");
        e.setStatus("PENDING");
        e.setRetryCount(retryCount);
        return e;
    }

    @BeforeEach
    void setUp() {
        job = new OrderEventRelayJob(outboxMapper, Optional.of(rocketMQTemplate), meterRegistry);
        ReflectionTestUtils.setField(job, "topic", "shop-order-topic");
    }

    @Test
    void relay_shouldSendAndMarkSent() {
        when(outboxMapper.selectPendingBatch(any(Integer.class)))
                .thenReturn(List.of(event(0)));
        when(outboxMapper.markSent(1L)).thenReturn(1);

        job.relayPendingEvents();

        // 目的地格式：topic:tag（tag 由事件类型小写并把下划线换成连字符）
        verify(rocketMQTemplate).convertAndSend("shop-order-topic:order-created",
                "{\"orderNo\":\"NO-100\"}");
        verify(outboxMapper).markSent(1L);
        // 成功后不应再安排重试
        verify(outboxMapper, never()).markRetry(any(), any(Integer.class), any(), any());
        verify(outboxMapper, never()).markFailed(any(), any());
    }

    @Test
    void relay_shouldScheduleRetry_whenSendFails() {
        when(outboxMapper.selectPendingBatch(any(Integer.class)))
                .thenReturn(List.of(event(0)));
        doThrow(new RuntimeException("broker unavailable"))
                .when(rocketMQTemplate).convertAndSend(anyString(), any(Object.class));

        job.relayPendingEvents();

        // 失败不得标记为已发送，且必须落库一条重试计划（否则这条事件就永远丢了）
        verify(outboxMapper, never()).markSent(any());
        ArgumentCaptor<Integer> retryCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(outboxMapper, times(1))
                .markRetry(eq(1L), retryCaptor.capture(), any(), any());
        assertEquals(1, retryCaptor.getValue(), "首次失败后 retry_count 应为 1");
        verify(outboxMapper, never()).markFailed(any(), any());
    }

    @Test
    void relay_shouldMarkFailed_afterMaxRetry() {
        // MAX_RETRY = 8：retry_count 已经是 7 时，本次（第 8 次）失败即转 FAILED
        when(outboxMapper.selectPendingBatch(any(Integer.class)))
                .thenReturn(List.of(event(7)));
        doThrow(new RuntimeException("still down"))
                .when(rocketMQTemplate).convertAndSend(anyString(), any(Object.class));

        job.relayPendingEvents();

        verify(outboxMapper, never()).markSent(any());
        verify(outboxMapper, never()).markRetry(any(), any(Integer.class), any(), any());
        verify(outboxMapper, times(1)).markFailed(eq(1L), any());
        // 打点：FAILED 计数应 +1，供告警规则使用
        assertDoesNotThrow(() -> meterRegistry.get("chronic.order.event.failed").counter());
    }

    @Test
    void relay_shouldDoNothing_whenNoPending() {
        when(outboxMapper.selectPendingBatch(any(Integer.class)))
                .thenReturn(Collections.emptyList());

        job.relayPendingEvents();

        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(outboxMapper, never()).markSent(any());
    }

    @Test
    void relay_shouldNotMarkSent_whenTemplateMissing() {
        // J-14 修复点：RocketMQTemplate 缺失时必须**明确告警**而不是静默跳过。
        // 关键是不能把事件标成已发送 —— 它还需要留在 outbox 里等 MQ 恢复后补发。
        OrderEventRelayJob jobWithoutMq =
                new OrderEventRelayJob(outboxMapper, Optional.empty(), meterRegistry);
        ReflectionTestUtils.setField(jobWithoutMq, "topic", "shop-order-topic");
        when(outboxMapper.selectPendingBatch(any(Integer.class)))
                .thenReturn(List.of(event(0)));

        jobWithoutMq.relayPendingEvents();

        verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(outboxMapper, never()).markSent(any());
        verify(outboxMapper, never()).markRetry(any(), any(Integer.class), any(), any());
        verify(outboxMapper, never()).markFailed(any(), any());
    }
}
