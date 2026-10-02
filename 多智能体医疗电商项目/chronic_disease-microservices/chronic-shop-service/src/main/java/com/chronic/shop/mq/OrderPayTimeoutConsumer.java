package com.chronic.shop.mq;

import com.chronic.shop.service.ShopOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 支付超时关单消费者：接收 {@link PayTimeoutPublisher} 发出的两段式延迟消息。
 * <p>
 * 消息体为 JSON {@code {"orderId":123,"phase":N}}，按阶段分发：
 * <ul>
 *   <li><b>phase 1</b>（下单后 30 分钟到点）：不关单——检查订单，仍 PENDING 就
 *       发出第二段宽限消息（再等 2 分钟）；这段缓冲吸收"用户在展示窗口最后一秒
 *       点支付、请求仍在途"的情况。已支付/已取消则直接结束。</li>
 *   <li><b>phase 2</b>（再过 2 分钟，即下单后 32 分钟到点）：真正的关单时点，
 *       CAS 关单（只关 PENDING）；已支付/已取消则幂等跳过。</li>
 * </ul>
 * 消息处理抛异常会触发 Broker 重投，数据库抖动时天然重试；消息体解析失败则
 * 直接吞掉（坏消息重投多少次都不会成功，避免无限重试）。
 * </p>
 *
 * @author chronic
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "chronic.mq.enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${chronic.pay.timeout-topic:order-pay-timeout-topic}",
        consumerGroup = "shop-order-pay-timeout-consumer"
)
public class OrderPayTimeoutConsumer implements RocketMQListener<String> {

    private final ShopOrderService shopOrderService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        long orderId;
        int phase;
        Long closeAtMs = null;
        try {
            JsonNode node = objectMapper.readTree(message);
            orderId = node.get("orderId").asLong();
            phase = node.path("phase").asInt(1);
            if (node.hasNonNull("closeAtMs")) {
                closeAtMs = node.get("closeAtMs").asLong();
            }
        } catch (Exception e) {
            log.error("[支付超时] 消息体解析失败，丢弃: {}", message);
            return;
        }
        // 防御：timer 模式的消息若被**立即**投递（broker 实际不支持时间轮，TIMER_DELIVER_MS
        // 被无视），绝不能提前关单——距关单时点还差 30 秒以上就视为"提前到达"，
        // 降级回 chain 模式（handlePayTimeout phase 1 会重新挂固定档位延迟消息）。
        if (closeAtMs != null && System.currentTimeMillis() < closeAtMs - 30_000) {
            log.error("[支付超时] 消息早于关单时点到达（broker 可能不支持时间轮），降级回 chain 模式: "
                    + "orderId={}, phase={}, closeAtMs={}", orderId, phase, closeAtMs);
            shopOrderService.handlePayTimeout(orderId, 1);
            return;
        }
        log.info("[支付超时] 收到延迟消息: orderId={}, phase={}", orderId, phase);
        shopOrderService.handlePayTimeout(orderId, phase);
    }
}
