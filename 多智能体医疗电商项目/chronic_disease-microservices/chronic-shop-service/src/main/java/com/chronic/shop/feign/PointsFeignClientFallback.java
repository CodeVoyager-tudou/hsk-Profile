package com.chronic.shop.feign;

import com.chronic.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 积分服务 Feign 调用熔断降级处理
 * <p>
 * 当积分服务不可用或响应超时时，由 Sentinel 触发此降级逻辑，
 * 避免积分服务故障拖垮商城服务，保证核心下单流程不受影响。
 * </p>
 *
 * @author chronic
 */
@Slf4j
@Component
public class PointsFeignClientFallback implements PointsFeignClient {

    /**
     * 增加积分降级：记录日志并返回 503，积分稍后人工补发
     */
    @Override
    public Result<Boolean> addPoints(Long userId, Integer points, String type, Long sourceId, String remark) {
        log.error("积分服务熔断降级: addPoints 调用失败, userId={}, points={}, type={}", userId, points, type);
        return Result.error(503, "积分服务暂时不可用，积分稍后补发");
    }

    /**
     * 扣减积分降级：返回 503 提示用户重试，避免商品被锁定
     */
    @Override
    public Result<Boolean> deductPoints(Long userId, Integer points, String type, Long sourceId, String remark) {
        log.error("积分服务熔断降级: deductPoints 调用失败, userId={}, points={}, type={}", userId, points, type);
        return Result.error(503, "积分服务暂时不可用，请稍后重试");
    }

    /**
     * 退还积分降级：返回 503，退款将稍后人工处理
     */
    @Override
    public Result<Boolean> refundPoints(Long userId, Integer points, String type, Long sourceId, String remark) {
        log.error("积分服务熔断降级: refundPoints 调用失败, userId={}, points={}, type={}, orderId={}",
                userId, points, type, sourceId);
        return Result.error(503, "积分服务暂时不可用，退款将稍后处理");
    }

    /**
     * 余额扣款降级：降级即视为"未扣款"（与积分兑换同口径）——本地事务回滚、订单撤销即可，
     * 不需要补偿台账；只有真超时（Feign 抛异常、结果不明）才由调用方写补偿对账。
     */
    @Override
    public Result<Boolean> deductBalance(Long userId, java.math.BigDecimal amount, String type,
                                         Long sourceId, String remark) {
        log.error("积分服务熔断降级: deductBalance 调用失败, userId={}, amount={}, orderId={}",
                userId, amount, sourceId);
        return Result.error(503, "支付服务暂时不可用，请稍后重试");
    }

    /**
     * 余额退款降级：返回 503，退款由补偿台账（BALANCE_REFUND）定时重试
     */
    @Override
    public Result<Boolean> refundBalance(Long userId, java.math.BigDecimal amount, String type,
                                         Long sourceId, String remark) {
        log.error("积分服务熔断降级: refundBalance 调用失败, userId={}, amount={}, orderId={}",
                userId, amount, sourceId);
        return Result.error(503, "支付服务暂时不可用，退款将稍后处理");
    }
}