package com.chronic.shop.job;

import com.chronic.shop.service.ShopOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付超时扫表兜底任务（本地 @Scheduled，不依赖调度中心）。
 *
 * <p><b>为什么已有 xxl-job 还要它：</b>超时关单的主路径是 RocketMQ 延迟消息，
 * 但消息链路存在极端丢失窗口（发送失败/消费重试耗尽进死信）。原先的扫表关单
 * {@code ShopJobHandler#closeExpiredOrderJob} 由 xxl-job 调度中心触发——
 * 本地开发/演示环境没有调度中心，兜底等于不存在。本任务用最朴素的
 * {@code @Scheduled} 把兜底落地为<b>永远在线</b>：关单 SQL 是
 * "仅 PENDING 可关"的原子 CAS，与延迟消息路径并发执行也幂等，双覆盖零副作用。</p>
 *
 * <p>扫描阈值 {@code chronic.pay.pending-timeout-minutes}（32 分钟）与延迟消息的
 * 真实关单时点一致；扫描频率 1 分钟，实际关单时间最多晚一分钟，可接受。</p>
 *
 * @author chronic
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutSweepJob {

    private final ShopOrderService shopOrderService;

    /** 兜底扫表开关：生产已有 xxl-job 时可置 false 关闭，避免双跑（双跑也无害，CAS 幂等） */
    @Value("${chronic.pay.timeout-sweep-enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void sweepExpiredPendingOrders() {
        if (!enabled) {
            return;
        }
        try {
            int closed = shopOrderService.closeExpiredPendingOrders();
            if (closed > 0) {
                log.warn("[支付超时兜底] 本轮扫表关闭超时未支付订单 {} 笔", closed);
            }
        } catch (Exception e) {
            log.error("[支付超时兜底] 扫表关单失败", e);
        }
    }
}
