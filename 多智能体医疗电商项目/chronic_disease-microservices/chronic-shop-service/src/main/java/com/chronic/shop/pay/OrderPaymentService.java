package com.chronic.shop.pay;

import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.feign.PointsFeignClient;
import com.chronic.shop.mapper.ShopOrderMapper;
import com.chronic.shop.mq.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 支付确认：把订单从 PENDING 推进到 PAID，并在支付成功后才发放积分、发订单事件。
 * <p>
 * 幂等设计：
 * <ul>
 *   <li>SQL 层 `UPDATE ... WHERE status='PENDING'` 原子抢占，只有一次能改成功（渠道重复回调安全）</li>
 *   <li>已 PAID 的订单再次确认直接返回原单，不重复发积分</li>
 *   <li>积分发放失败时保持 points_status=0，由 PointsCompensateJob 补发（流水唯一键兜底不重复加分）</li>
 * </ul>
 *
 * @author chronic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaymentService {

    private final ShopOrderMapper shopOrderMapper;
    private final PointsFeignClient pointsFeignClient;
    private final OrderEventPublisher orderEventPublisher;

    /**
     * 确认支付（幂等）
     */
    @Transactional(rollbackFor = Exception.class)
    public ShopOrder confirmPaid(Long orderId) {
        ShopOrder order = shopOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if ("PAID".equals(order.getStatus())) {
            log.info("订单已支付，重复确认直接返回: orderNo={}", order.getOrderNo());
            return order;
        }
        if (!"PENDING".equals(order.getStatus())) {
            throw new BusinessException("订单当前状态不可支付: " + order.getStatus());
        }
        if (shopOrderMapper.confirmPaid(orderId) == 0) {
            // CAS 落空 = 并发下订单状态已被别人改走。两种可能，语义完全不同，不能都当成功：
            //   · 被另一次支付回调确认成 PAID → 幂等成功，返回原单；
            //   · 被支付超时延迟消息关单成 CANCELLED（用户卡在最后一秒点支付）→
            //     必须抛业务错误，绝不能把已取消的订单当"支付成功"返回。
            ShopOrder latest = shopOrderMapper.selectById(orderId);
            if (latest != null && "PAID".equals(latest.getStatus())) {
                log.info("订单已支付，重复确认直接返回: orderNo={}", latest.getOrderNo());
                return latest;
            }
            throw new BusinessException("订单已超时关闭或已取消，无法继续支付，请重新下单");
        }
        order.setStatus("PAID");
        order.setPayTime(LocalDateTime.now());
        grantPoints(order);
        // J-14：改为在**当前事务内**把订单事件写入 outbox（而不是提交后直接发 MQ）。
        // 这样"订单已支付"与"事件已被记录"是同一个事务，不可能只成功一半；
        // 真正投递由 OrderEventRelayJob 负责，Broker 抖动只会让事件晚到而不会丢失。
        orderEventPublisher.enqueue(order);
        log.info("订单支付成功: orderNo={}, userId={}, amount={}", order.getOrderNo(), order.getUserId(),
                Optional.ofNullable(order.getTotalAmount()).orElse(java.math.BigDecimal.ZERO)
                        .subtract(Optional.ofNullable(order.getDiscountAmount()).orElse(java.math.BigDecimal.ZERO)));
        return order;
    }

    /**
     * 支付成功后发放积分；失败保持待补偿状态（不抛异常，避免把已成功的支付回滚掉）
     */
    private void grantPoints(ShopOrder order) {
        Integer pointsEarned = Optional.ofNullable(order.getPointsEarned()).orElse(0);
        if (pointsEarned <= 0) {
            return;
        }
        try {
            Result<Boolean> result = pointsFeignClient.addPoints(order.getUserId(), pointsEarned, "ORDER_PURCHASE",
                    order.getId(), "购买 " + order.getMedicineName() + " 获得积分");
            if (result != null && result.getCode() != null && result.getCode() == 200) {
                shopOrderMapper.markPointsGranted(order.getId());
                order.setPointsStatus(1);
            } else {
                log.error("积分发放失败，等待补偿任务补发，订单号: {}, 原因: {}",
                        order.getOrderNo(), result == null ? "积分服务不可用" : result.getMessage());
            }
        } catch (Exception e) {
            log.error("积分发放调用异常，等待补偿任务补发，订单号: {}", order.getOrderNo(), e);
        }
    }
}
