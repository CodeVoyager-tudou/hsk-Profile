package com.chronic.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单事件本地消息表（Outbox）—— J-14 修复。
 *
 * <h3>【小白先看：为什么需要这张表】</h3>
 * 下单成功后要通知别的系统（发积分、发短信、风控……），这件事靠发 MQ 消息完成。
 * 但「写数据库」和「发消息」是两件独立的事，做不到要么都成功：
 * <pre>
 *   订单已入库（用户看到下单成功） → 发消息时 Broker 抖了一下 → 消息没发出去
 *   → 下游永远不知道有这笔订单，而我们已经把这条消息忘掉了
 * </pre>
 * 这叫「双写不一致」。
 *
 * <p>解决办法叫 <b>Transactional Outbox（本地消息表）</b>：
 * 把「要发的消息」当成一条普通数据，和订单写在**同一个数据库事务**里。
 * 这样订单和消息要么一起成功、要么一起回滚，绝不会只成功一个。
 * 随后由一个定时任务（{@code OrderEventRelayJob}）慢慢把 PENDING 的消息真正发出去。</p>
 *
 * <p>类比：与其"打完电话才记账"（可能忘了记），不如"先记账再打，打完划掉"——
 * 即使电话没打通，账还在，可以重打。</p>
 */
@Data
@TableName("order_event_outbox")
public class OrderEventOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单ID（事件所属的业务对象） */
    private Long orderId;

    /** 订单号，仅用于日志排查时人能看懂 */
    private String orderNo;

    /** 事件类型，如 ORDER_CREATED */
    private String eventType;

    /** 事件载荷（JSON），消费者需要的完整信息都在这里 */
    private String payload;

    /** PENDING-待发送 / SENT-已发送 / FAILED-超过重试上限需人工 */
    private String status;

    private Integer retryCount;

    private String lastError;

    private LocalDateTime nextRetryTime;

    private LocalDateTime sentTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
