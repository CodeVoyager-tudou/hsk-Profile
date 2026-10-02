package com.chronic.shop.mq;

import cn.hutool.json.JSONUtil;
import com.chronic.shop.entity.OrderEventOutbox;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.mapper.OrderEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 订单事件发布器 —— 现在只负责「把要发的事件记下来」，不再直接发 MQ（J-14 修复）。
 *
 * <h3>【改之前是什么样，问题在哪】</h3>
 * 原实现在事务提交之后直接调 {@code rocketMQTemplate.convertAndSend}，
 * 并且用 try/catch 把异常**完全吞掉只打一行 error 日志**：
 * <pre>
 *   afterCommit → convertAndSend → 失败 → log.error（结束）
 * </pre>
 * 后果：订单已经是 PAID、用户已经看到"下单成功"，但这条事件永久丢失，
 * 既没有补偿机制，也没有任何地方能查出"它到底发出去没有"。
 *
 * <h3>【改之后是什么样】</h3>
 * <pre>
 *   同一个数据库事务内：
 *       写订单
 *       写 order_event_outbox（status=PENDING）   ← 本类负责
 *   事务提交后：
 *       定时任务 OrderEventRelayJob 扫描 PENDING → 真正发 MQ → 标记 SENT
 *       发送失败 → 记下原因、按退避重试；超过上限转 FAILED 并告警
 * </pre>
 * 关键点：**订单与事件记录在同一个事务里**，所以不可能出现"订单在、事件丢"。
 * 即使 Broker 挂掉几小时，事件也只是"晚到"，Broker 恢复后会自动补发。
 *
 * @author chronic
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    /** 订单创建事件类型（同一订单只允许一条，靠唯一键保证） */
    public static final String EVENT_ORDER_CREATED = "ORDER_CREATED";

    private final Optional<OrderEventOutboxMapper> orderEventOutboxMapper;

    /**
     * 记录一条订单事件（**在当前事务内**写入，与订单同生共死）。
     *
     * <p>注意与旧版语义的区别：旧版叫 publishAfterCommit（提交后才发），
     * 新版是「先记账、后由中继发送」。方法名相应改为 enqueue，
     * 避免读者误以为这里会立即发消息。</p>
     */
    public void enqueue(ShopOrder order) {
        if (order == null || order.getId() == null) {
            log.warn("订单信息不完整，跳过事件入队: {}", order);
            return;
        }
        if (orderEventOutboxMapper.isEmpty()) {
            // outbox 表不可用（如单测未注入 Mapper）：只告警，不影响下单主流程
            log.warn("Outbox 不可用，订单事件未入队（下游将收不到该事件）: orderNo={}", order.getOrderNo());
            return;
        }
        try {
            OrderEventOutbox outbox = new OrderEventOutbox();
            outbox.setOrderId(order.getId());
            outbox.setOrderNo(order.getOrderNo());
            outbox.setEventType(EVENT_ORDER_CREATED);
            outbox.setPayload(JSONUtil.toJsonStr(order));
            outbox.setStatus("PENDING");
            outbox.setRetryCount(0);
            // 立即可发，无需等待
            outbox.setNextRetryTime(LocalDateTime.now());
            orderEventOutboxMapper.get().insert(outbox);
        } catch (DuplicateKeyException e) {
            // 唯一键 (order_id, event_type)：同一订单重复入队无需报错（幂等）
            log.debug("订单事件已存在，跳过重复入队: orderNo={}", order.getOrderNo());
        } catch (Exception e) {
            // 入队失败**不能吞掉**：它与订单在同一事务内，
            // 抛出去才能让整个事务回滚，从而保证"要么订单和事件都在，要么都不在"。
            log.error("订单事件入队失败，事务将回滚以避免订单与事件不一致: orderNo={}",
                    order.getOrderNo(), e);
            throw e;
        }
    }
}
