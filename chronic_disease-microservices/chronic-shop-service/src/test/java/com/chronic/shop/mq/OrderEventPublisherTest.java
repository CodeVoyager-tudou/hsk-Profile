package com.chronic.shop.mq;

import com.chronic.shop.entity.OrderEventOutbox;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.mapper.OrderEventOutboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单事件 outbox 的回归测试（J-14）。
 *
 * <p>缺陷背景：原实现是「事务提交后直接发 MQ + 异常只打日志」，
 * 后果是订单已 PAID 但事件永久丢失，且无法重放。
 * 修复后改为「事务内写 outbox，由中继任务负责投递」。
 * 本测试锁定这一行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock
    private OrderEventOutboxMapper outboxMapper;

    private ShopOrder order() {
        ShopOrder o = new ShopOrder();
        o.setId(100L);
        o.setOrderNo("NO-100");
        o.setUserId(1L);
        o.setStatus("PAID");
        return o;
    }

    @Test
    void enqueue_shouldInsertPendingOutboxRow() {
        OrderEventPublisher publisher = new OrderEventPublisher(Optional.of(outboxMapper));

        publisher.enqueue(order());

        ArgumentCaptor<OrderEventOutbox> captor = ArgumentCaptor.forClass(OrderEventOutbox.class);
        verify(outboxMapper).insert(captor.capture());
        OrderEventOutbox saved = captor.getValue();
        assertEquals(100L, saved.getOrderId());
        assertEquals("NO-100", saved.getOrderNo());
        assertEquals(OrderEventPublisher.EVENT_ORDER_CREATED, saved.getEventType());
        assertEquals("PENDING", saved.getStatus(), "入队时必须先是 PENDING，等中继发送后才变 SENT");
        assertEquals(0, saved.getRetryCount());
        // 立即到期：让中继任务下一轮就能捞到，不需要额外等待
        assertNotNull(saved.getNextRetryTime());
        // 载荷必须是该订单的 JSON，消费者据此还原业务信息
        assertNotNull(saved.getPayload());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getPayload().contains("NO-100"),
                "载荷应包含订单号，实际: " + saved.getPayload());
    }

    @Test
    void enqueue_shouldBeIdempotent_whenDuplicate() {
        // 唯一键 (order_id, event_type) 撞键说明这条事件已入队过 —— 属正常幂等，不能抛错
        when(outboxMapper.insert(any(OrderEventOutbox.class)))
                .thenThrow(new DuplicateKeyException("uk_order_event"));
        OrderEventPublisher publisher = new OrderEventPublisher(Optional.of(outboxMapper));

        assertDoesNotThrow(() -> publisher.enqueue(order()));
    }

    @Test
    void enqueue_shouldRethrow_whenInsertFailsForOtherReason() {
        // 关键：非幂等键的失败必须**向上抛**，让订单事务一起回滚。
        // 如果这里吞掉异常，就会出现"订单在、事件丢" —— 正是 J-14 要修的问题。
        when(outboxMapper.insert(any(OrderEventOutbox.class)))
                .thenThrow(new RuntimeException("db down"));
        OrderEventPublisher publisher = new OrderEventPublisher(Optional.of(outboxMapper));

        assertThrows(RuntimeException.class, () -> publisher.enqueue(order()));
    }

    @Test
    void enqueue_shouldNotThrow_whenOutboxUnavailable() {
        // outbox 不可用（如单测未注入 Mapper）时不阻断下单主流程，只告警
        OrderEventPublisher publisher = new OrderEventPublisher(Optional.empty());

        assertDoesNotThrow(() -> publisher.enqueue(order()));
        verify(outboxMapper, never()).insert(any(OrderEventOutbox.class));
    }

    @Test
    void enqueue_shouldSkip_whenOrderHasNoId() {
        OrderEventPublisher publisher = new OrderEventPublisher(Optional.of(outboxMapper));
        ShopOrder noId = new ShopOrder();
        noId.setOrderNo("NO-X");

        assertDoesNotThrow(() -> publisher.enqueue(noId));
        verify(outboxMapper, never()).insert(any(OrderEventOutbox.class));
    }
}
