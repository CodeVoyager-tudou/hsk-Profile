package com.chronic.shop.mq;

import cn.hutool.json.JSONUtil;
import com.chronic.shop.service.SeckillService;
import com.chronic.shop.service.impl.SeckillServiceImpl.SeckillMessage;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 秒杀排队消息消费者：把"Lua 预扣成功"的请求真正落库。
 *
 * <h3>【与订单事件消费者的区别】</h3>
 * 订单事件（order-created）是<b>通知</b>，失败重投无害；秒杀消息是<b>指令</b>，
 * 里面装着"谁抢到了名额"，落库失败会让用户白等。所以本消费者对失败的策略是
 * <b>消费成功 + 结果写 Redis（FAILED）</b>，而不是抛异常触发 MQ 重试：
 * <ul>
 *   <li>业务失败（余额不足/已抢完）：重试也不会成功，写结果让用户尽快看到；</li>
 *   <li>系统异常：幂等由 requestId 唯一键保证，可安全重试的场景交给
 *       用户重抢（名额已回补）；拿不准的（扣款结果不明）走补偿台账，不重试。</li>
 * </ul>
 *
 * @author chronic
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "chronic.mq.enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${chronic.shop.seckill.topic:shop-seckill-topic}",
        consumerGroup = "${chronic.shop.seckill.consumer-group:chronic-shop-seckill-consumer}")
public class SeckillConsumer implements RocketMQListener<String> {

    private final SeckillService seckillService;
    private final MeterRegistry meterRegistry;

    @Override
    public void onMessage(String message) {
        SeckillMessage msg;
        try {
            msg = JSONUtil.toBean(message, SeckillMessage.class);
        } catch (Exception e) {
            log.error("秒杀消息解析失败，丢弃: {}", message, e);
            return;
        }
        if (msg.userId == null || msg.activityId == null || msg.requestId == null) {
            log.error("秒杀消息字段缺失，丢弃: {}", message);
            return;
        }
        log.info("秒杀消息落库开始: activityId={}, userId={}, requestId={}",
                msg.activityId, msg.userId, msg.requestId);
        seckillService.processSeckill(msg.activityId, msg.userId, msg.requestId);
        meterRegistry.counter("chronic.seckill.processed").increment();
    }
}
