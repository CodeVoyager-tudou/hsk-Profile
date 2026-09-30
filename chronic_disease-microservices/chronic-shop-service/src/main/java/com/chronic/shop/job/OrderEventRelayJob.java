package com.chronic.shop.job;

import com.chronic.shop.entity.OrderEventOutbox;
import com.chronic.shop.mapper.OrderEventOutboxMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 订单事件中继任务 —— 把 outbox 里 PENDING 的事件真正发到 MQ（J-14 修复）。
 *
 * <h3>【小白先看：它在整个链路里的位置】</h3>
 * <pre>
 *   下单事务：写订单 + 写 outbox(PENDING)      ← 同事务，绝不会只成功一半
 *   ─────────────────────────────────────
 *   本任务（每 10 秒）：捞 PENDING → 发 MQ → 成功改 SENT / 失败按退避重试
 * </pre>
 * 它是「至少一次投递」的保证来源：只要记录还在 PENDING，就会一直重试，
 * 直到成功或超过上限转人工。因此 Broker 短暂故障不会丢事件。
 *
 * <h3>【为什么能做到"重复发送也不会重复处理"】</h3>
 * 中继可能因为"发送成功但标记 SENT 前进程重启"而重发同一条消息，
 * 所以下游消费者必须做幂等（通常用 orderNo 或事件唯一键去重）。
 * 这一点已在 {@code OrderEventConsumer} 的注释中说明。
 *
 * @author chronic
 */
@Slf4j
@Component
public class OrderEventRelayJob {

    /** 单轮最多处理多少条，防止一次拉太多把内存占满 */
    private static final int BATCH_LIMIT = 100;

    /** 超过这个次数仍失败则转 FAILED，等人介入 */
    private static final int MAX_RETRY = 8;

    private final OrderEventOutboxMapper outboxMapper;
    /** RocketMQTemplate 可能不存在（未接 MQ 的部署）：此时只记录、不发送 */
    private final Optional<RocketMQTemplate> rocketMQTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${chronic.shop.order-event.topic:shop-order-topic}")
    private String topic;

    public OrderEventRelayJob(OrderEventOutboxMapper outboxMapper,
                              Optional<RocketMQTemplate> rocketMQTemplate,
                              MeterRegistry meterRegistry) {
        this.outboxMapper = outboxMapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 每 10 秒跑一轮。
     * <p>间隔取 10 秒是因为原始事件其实已经在事务提交时"入库"了，
     * 这里只是把投递延迟控制在可接受范围内；不必像补偿任务那样等几分钟。</p>
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void relayPendingEvents() {
        List<OrderEventOutbox> pending = outboxMapper.selectPendingBatch(BATCH_LIMIT);
        if (pending.isEmpty()) {
            return;
        }
        if (rocketMQTemplate.isEmpty()) {
            // J-14 修复点之一：RocketMQTemplate 缺失时必须**明确告警**，
            // 而不是像旧版那样静默跳过（旧版 ifPresent 什么都不做，事件就这么没了）。
            log.warn("RocketMQTemplate 未装配，{} 条订单事件无法投递（将一直留在 outbox 中等待）",
                    pending.size());
            return;
        }
        log.info("订单事件中继开始，待投递 {} 条", pending.size());
        int success = 0;
        for (OrderEventOutbox event : pending) {
            if (sendOne(event)) {
                success++;
            }
        }
        log.info("订单事件中继结束：成功 {}/{}", success, pending.size());
    }

    private boolean sendOne(OrderEventOutbox event) {
        String destination = topic + ":" + event.getEventType().toLowerCase().replace('_', '-');
        try {
            rocketMQTemplate.get().convertAndSend(destination, event.getPayload());
            int updated = outboxMapper.markSent(event.getId());
            if (updated > 0) {
                meterRegistry.counter("chronic.order.event.sent").increment();
                log.debug("订单事件已投递: orderNo={}, destination={}", event.getOrderNo(), destination);
            }
            return true;
        } catch (Exception e) {
            scheduleRetry(event, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    /** 失败退避：1、2、4、8… 分钟，上限 30 分钟；超过上限转 FAILED 并打点告警 */
    private void scheduleRetry(OrderEventOutbox event, String error) {
        int retry = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        String message = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        if (retry >= MAX_RETRY) {
            outboxMapper.markFailed(event.getId(), message);
            meterRegistry.counter("chronic.order.event.failed").increment();
            log.error("订单事件投递超过重试上限，需人工介入！orderNo={}, 最后原因={}",
                    event.getOrderNo(), message);
            return;
        }
        long delayMinutes = Math.min(30L, 1L << retry);
        outboxMapper.markRetry(event.getId(), retry, message,
                LocalDateTime.now().plusMinutes(delayMinutes));
        log.warn("订单事件投递失败，{} 分钟后重试（第 {} 次）: orderNo={}, 原因={}",
                delayMinutes, retry, event.getOrderNo(), message);
    }
}
