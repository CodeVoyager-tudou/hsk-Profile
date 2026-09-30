package com.chronic.shop.mq;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 订单事件消费者（J-14 修复的一半：补齐「零消费者」）。
 *
 * <h3>【为什么之前是问题】</h3>
 * 审计发现：全代码库搜不到任何 {@code @RocketMQMessageListener}，
 * 也就是说订单事件**只发不收**。虽然这不影响下单主流程，
 * 但意味着"消息链路"从未被真正走通过 —— 一旦将来接入下游（发积分、短信通知、风控），
 * 消息格式/序列化/重试语义里隐藏的问题会集中爆发，而且没人能提前发现。
 *
 * <h3>【本消费者做什么】</h3>
 * 当前阶段它只做两件**确实有用**的事，不臆造业务逻辑：
 * <ol>
 *   <li>把收到的事件写进日志并打点 —— 让"消息是否真的送达"可观测、可排查；</li>
 *   <li>把链路真正打通 —— 中继任务发出的消息会被实际消费，
 *       从而能在集成环境验证 outbox 的完整闭环。</li>
 * </ol>
 * 将来要接入真实业务（例如"下单后发短信"），在这里追加即可。
 *
 * <h3>【⚠️ 下游必须做幂等】</h3>
 * outbox 采用「至少一次投递」语义，因此**同一个事件可能被消费多次**，典型场景：
 * <pre>
 *   中继发出消息 → Broker 已收到 → 但在标记 SENT 之前进程重启
 *   → 该事件仍是 PENDING → 下一轮重试再发一次 → 消费者收到第二条一模一样的消息
 * </pre>
 * 所以真实业务消费者必须按 {@code orderNo}（或事件唯一键）去重，
 * 不能假设"每条消息只会来一次"。
 *
 * @author chronic
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "chronic.mq.enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${chronic.shop.order-event.topic:shop-order-topic}",
        selectorExpression = "order-created",
        consumerGroup = "${chronic.shop.order-event.consumer-group:chronic-shop-order-event-consumer}")
public class OrderEventConsumer implements RocketMQListener<String> {

    private final MeterRegistry meterRegistry;

    @Override
    public void onMessage(String message) {
        // 这里只记录与打点，不做业务处理 —— 真正的下游（积分/通知/风控）应各自建消费者组，
        // 不要复用本组，否则它们会争抢同一条消息（RocketMQ 集群消费模式下同组内只投递一份）。
        meterRegistry.counter("chronic.order.event.consumed").increment();
        log.info("收到订单事件: {}", message);
    }
}
