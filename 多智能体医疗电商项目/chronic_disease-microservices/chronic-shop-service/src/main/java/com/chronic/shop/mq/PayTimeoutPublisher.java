package com.chronic.shop.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 支付超时延迟消息发布器（双模式，为 RocketMQ 5.x 升级预留）。
 *
 * <h3>业务语义（两种模式一致）</h3>
 * 前端向用户展示的支付窗口是 30 分钟，系统真正的关单时点是 <b>32 分钟</b>——
 * 多出的 2 分钟是<b>宽限期</b>，吸收"用户在展示窗口最后一秒点支付、请求仍在途"的
 * 情况。展示时间(30m) ≤ 真实窗口(32m)，到期由消费者按 CAS 关单（只关 PENDING）。
 *
 * <h3>mode = chain（默认，兼容 4.x broker）</h3>
 * RocketMQ 4.x 延迟消息只有 18 个固定档（…20m <b>30m</b> 1h…），没有 32m 这一档，
 * 用<b>两段串联</b>凑出 30m+2m：
 * <ol>
 *   <li>第一段（level 16 = 30m）到点：消费者<b>不关单</b>，检查订单——仍 PENDING
 *       就发第二段（进入宽限期）；已支付/已取消则结束。</li>
 *   <li>第二段（level 6 = 2m）到点：执行 CAS 关单（真实关单时点 = 32m）。</li>
 * </ol>
 *
 * <h3>mode = timer（broker 升级 5.x / 4.9+ 并开启时间轮后可选）</h3>
 * 时间轮支持<b>任意时长</b>：下单时直接发一条"到点投递"的消息
 * （用户属性 {@code TIMER_DELIVER_MS} = 关单时间戳，= 下单时刻 + 32 分钟），
 * 消费者收到即执行关单——省掉两段串联。⚠️ 若 broker 实际不支持时间轮，
 * 消息会被<b>立即</b>投递，消费者会按 closeAtMs 识别出"提前到达"并自动降级回
 * chain 模式，不会提前关单（见 OrderPayTimeoutConsumer 的防御）。
 *
 * <p>消息体为 JSON：{@code {"orderId":123,"phase":N,"closeAtMs":T}}。
 * 发送失败只记日志不抛出：超时关单是兜底逻辑，不能因为它影响下单主流程；
 * 漏发由扫表任务（OrderTimeoutSweepJob / xxl-job）兜底。</p>
 *
 * @author chronic
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayTimeoutPublisher {

    private final Optional<RocketMQTemplate> rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Value("${chronic.pay.timeout-topic:order-pay-timeout-topic}")
    private String topic;

    /** 发送模式：chain = 两段固定档位（4.x 兼容，默认）；timer = 单条任意延迟（5.x 时间轮） */
    @Value("${chronic.pay.timeout-mode:${PAY_TIMEOUT_MODE:chain}}")
    private String timeoutMode;

    /** chain 模式第一段延迟级别：默认 16 = 30 分钟（展示给用户的支付窗口） */
    @Value("${chronic.pay.timeout-delay-level:16}")
    private int timeoutDelayLevel;

    /** chain 模式第二段（宽限期）延迟级别：默认 6 = 2 分钟 */
    @Value("${chronic.pay.timeout-grace-delay-level:6}")
    private int graceDelayLevel;

    /** 真实关单时点 = 下单时刻 + 该分钟数（timer 模式的投递时间；扫表任务的扫描阈值） */
    @Value("${chronic.pay.pending-timeout-minutes:32}")
    private int pendingTimeoutMinutes;

    /** 下单时调用：按当前模式挂超时消息（chain → 第一段；timer → 单条到点投递） */
    public void publishOrderTimeout(Long orderId, String orderNo) {
        if ("timer".equalsIgnoreCase(timeoutMode)) {
            // timer 模式：一条消息，投递时间 = 现在 + pendingTimeoutMinutes
            long closeAtMs = System.currentTimeMillis() + pendingTimeoutMinutes * 60_000L;
            send(orderId, orderNo, 2, null, closeAtMs);
            log.info("[支付超时] 已发送定时消息（timer 模式）: orderNo={}, orderId={}, 关单时点=+{}分钟",
                    orderNo, orderId, pendingTimeoutMinutes);
            return;
        }
        // chain 模式：第一段（30m 到点后由消费者检查并决定是否进入宽限期）
        send(orderId, orderNo, 1, timeoutDelayLevel, null);
        log.info("[支付超时] 已发送第一段延迟消息（chain 模式）: orderNo={}, orderId={}, delayLevel={}（30 分钟后检查）",
                orderNo, orderId, timeoutDelayLevel);
    }

    /** 宽限期重挂（chain 模式第一段到点、订单仍 PENDING 时由服务层调用）：2 分钟后真正关单 */
    public void publishGrace(Long orderId, String orderNo) {
        send(orderId, orderNo, 2, graceDelayLevel, null);
        log.info("[支付超时] 已发送第二段宽限延迟消息: orderNo={}, orderId={}, delayLevel={}（2 分钟后关单检查）",
                orderNo, orderId, graceDelayLevel);
    }

    private void send(Long orderId, String orderNo, int phase, Integer delayLevel, Long closeAtMs) {
        if (rocketMQTemplate.isEmpty()) {
            log.warn("[支付超时] RocketMQTemplate 未装配，跳过延迟消息: orderNo={}, phase={}", orderNo, phase);
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", orderId);
            payload.put("phase", phase);
            if (closeAtMs != null) {
                payload.put("closeAtMs", closeAtMs);
            }
            org.springframework.messaging.Message<String> message = MessageBuilder
                    .withPayload(objectMapper.writeValueAsString(payload))
                    .setHeader("ORDER_NO", orderNo)
                    .build();
            if (delayLevel != null) {
                rocketMQTemplate.get().syncSend(topic, message, 3000, delayLevel);
            } else {
                // timer 模式：投递时间走用户属性 TIMER_DELIVER_MS（毫秒时间戳），
                // 5.x broker（timerEnable=true）按时间轮到点投递；不支持的 broker 会立即投递，
                // 由消费者的 closeAtMs 防御识别并降级回 chain 模式
                rocketMQTemplate.get().syncSend(topic,
                        MessageBuilder.fromMessage(message)
                                .setHeader("TIMER_DELIVER_MS", String.valueOf(closeAtMs))
                                .build());
            }
        } catch (Exception e) {
            log.error("[支付超时] 延迟消息发送失败: orderNo={}, orderId={}, phase={}", orderNo, orderId, phase, e);
        }
    }
}
