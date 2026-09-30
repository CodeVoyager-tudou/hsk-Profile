package com.chronic.shop.job;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.chronic.shop.entity.UserCoupon;
import com.chronic.shop.mapper.UserCouponMapper;
import com.chronic.shop.service.ShopOrderService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 商城定时任务：优惠券过期标记、超时未支付订单关单
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopJobHandler {

    private final UserCouponMapper userCouponMapper;
    private final ShopOrderService shopOrderService;

    /**
     * 每天 01:00：把已过有效期但仍为 UNUSED 的券标记为 EXPIRED
     */
    @XxlJob("expireCouponJob")
    public void expireCouponJob() {
        log.info("开始标记过期优惠券...");
        try {
            int rows = userCouponMapper.update(null, new LambdaUpdateWrapper<UserCoupon>()
                    .eq(UserCoupon::getStatus, "UNUSED")
                    .lt(UserCoupon::getExpireTime, java.time.LocalDateTime.now())
                    .set(UserCoupon::getStatus, "EXPIRED"));
            log.info("过期优惠券标记完成，共 {} 张", rows);
        } catch (Exception e) {
            log.error("过期优惠券标记失败", e);
            throw e;
        }
    }

    /**
     * 每 5 分钟：关闭超时未支付订单（PENDING 超过配置时长），归还库存与优惠券。
     * 不加这个任务，未支付的 PENDING 单会永久占用库存和优惠券。
     */
    @XxlJob("closeExpiredOrderJob")
    public void closeExpiredOrderJob() {
        try {
            int closed = shopOrderService.closeExpiredPendingOrders();
            log.info("超时未支付订单关闭完成，本轮 {} 笔", closed);
        } catch (Exception e) {
            log.error("超时未支付订单关闭失败", e);
            throw e;
        }
    }
}
