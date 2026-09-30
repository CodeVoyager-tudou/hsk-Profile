package com.chronic.shop.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.feign.PointsFeignClient;
import com.chronic.shop.mapper.ShopOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 积分补偿任务：自动补发"下单赠积分"发放失败的订单。
 *
 * <p>背景：createOrder 里积分发放经 Feign + Sentinel 降级，积分服务不可用时返回错误码，
 * 订单主流程照常完成，订单的 points_status 保持 0（待发放）。本任务周期性扫描这类订单重试发放。</p>
 *
 * <p>幂等保障：points_record 的 (type, source_id) 唯一键——重试撞键时积分服务按"已处理"返回，
 * 余额不会重复累加，因此本任务重复执行/与主流程并发执行都是安全的。</p>
 *
 * <p>用 @Scheduled 而非 xxl-job：补偿属自愈逻辑，不依赖调度控制台配置即可生效。</p>
 *
 * @author chronic
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsCompensateJob {

    private static final int BATCH_LIMIT = 100;

    private final ShopOrderMapper shopOrderMapper;
    private final PointsFeignClient pointsFeignClient;

    /** 每 5 分钟一轮，单轮最多 100 单；只处理支付成功 5 分钟后的订单（避开主流程刚失败、Feign 尚在恢复的窗口） */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void compensateOrderPoints() {
        List<ShopOrder> pending = shopOrderMapper.selectList(new LambdaQueryWrapper<ShopOrder>()
                .eq(ShopOrder::getStatus, "PAID")
                .eq(ShopOrder::getPayType, "CASH")
                .eq(ShopOrder::getPointsStatus, 0)
                .gt(ShopOrder::getPointsEarned, 0)
                .lt(ShopOrder::getCreateTime, LocalDateTime.now().minusMinutes(5))
                .last("LIMIT " + BATCH_LIMIT));
        if (pending.isEmpty()) {
            return;
        }
        log.info("积分补偿任务开始，待补发订单 {} 笔", pending.size());
        int success = 0;
        for (ShopOrder order : pending) {
            try {
                Result<Boolean> result = pointsFeignClient.addPoints(order.getUserId(), order.getPointsEarned(),
                        "ORDER_PURCHASE", order.getId(), "积分补偿任务补发（订单 " + order.getOrderNo() + "）");
                if (result != null && result.getCode() != null && result.getCode() == 200) {
                    // 条件更新防并发：只有仍为待发放时才标记为已发放
                    shopOrderMapper.update(null, new LambdaUpdateWrapper<ShopOrder>()
                            .eq(ShopOrder::getId, order.getId())
                            .eq(ShopOrder::getPointsStatus, 0)
                            .set(ShopOrder::getPointsStatus, 1));
                    success++;
                    log.info("补偿积分发放成功: orderNo={}, points={}", order.getOrderNo(), order.getPointsEarned());
                } else {
                    log.warn("补偿积分发放仍失败，下一轮重试: orderNo={}, 原因: {}",
                            order.getOrderNo(), result == null ? "积分服务不可用" : result.getMessage());
                }
            } catch (Exception e) {
                log.error("补偿积分任务异常，下一轮重试: orderNo={}", order.getOrderNo(), e);
            }
        }
        log.info("积分补偿任务结束：成功 {}/{}", success, pending.size());
    }
}
