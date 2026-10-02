package com.chronic.shop.feign;

import com.chronic.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * 积分服务远程调用客户端（OpenFeign + Sentinel 熔断降级）
 * <p>
 * 通过 Nacos 服务发现自动路由到 chronic-points-service，
 * 当积分服务不可用时由 {@link PointsFeignClientFallback} 兜底处理。
 * </p>
 *
 */
@FeignClient(name = "chronic-points-service", fallback = PointsFeignClientFallback.class)
public interface PointsFeignClient {

    /**
     * 增加积分（购买药品后发放积分奖励）
     *
     * @param userId   用户ID
     * @param points   积分数量
     * @param type     积分类型（ORDER_PURCHASE / SIGN_IN 等）
     * @param sourceId 来源ID（订单ID / 签到记录ID）
     * @param remark   备注说明
     * @return 操作结果
     */
    @PostMapping("/points/add")
    Result<Boolean> addPoints(@RequestParam("userId") Long userId,
                              @RequestParam("points") Integer points,
                              @RequestParam("type") String type,
                              @RequestParam("sourceId") Long sourceId,
                              @RequestParam("remark") String remark);

    /**
     * 扣减积分（积分兑换药品时消耗积分）
     *
     * @param userId   用户ID
     * @param points   积分数量
     * @param type     积分类型（POINTS_EXCHANGE）
     * @param sourceId 来源ID（订单ID）
     * @param remark   备注说明
     * @return 操作结果，余额不足时返回 code != 200
     */
    @PostMapping("/points/deduct")
    Result<Boolean> deductPoints(@RequestParam("userId") Long userId,
                                 @RequestParam("points") Integer points,
                                 @RequestParam("type") String type,
                                 @RequestParam("sourceId") Long sourceId,
                                 @RequestParam("remark") String remark);

    /**
     * 退还积分（兑换订单取消时退回已扣积分）
     *
     * @param userId   用户ID
     * @param points   积分数量
     * @param type     积分类型（POINTS_REFUND）
     * @param sourceId 来源ID（订单ID）
     * @param remark   备注说明
     * @return 操作结果
     */
    @PostMapping("/points/refund")
    Result<Boolean> refundPoints(@RequestParam("userId") Long userId,
                                 @RequestParam("points") Integer points,
                                 @RequestParam("type") String type,
                                 @RequestParam("sourceId") Long sourceId,
                                 @RequestParam("remark") String remark);

    /**
     * 余额扣款（余额支付）：流水 (type, source_id) 唯一键幂等；余额不足返回 code != 200
     *
     * @param userId   用户ID
     * @param amount   扣款金额（元）
     * @param type     资金类型（BALANCE_PAY / BALANCE_PAY_ROLLBACK）
     * @param sourceId 来源ID（订单ID）
     * @param remark   备注说明
     * @return 操作结果
     */
    @PostMapping("/account/deduct")
    Result<Boolean> deductBalance(@RequestParam("userId") Long userId,
                                  @RequestParam("amount") BigDecimal amount,
                                  @RequestParam("type") String type,
                                  @RequestParam("sourceId") Long sourceId,
                                  @RequestParam("remark") String remark);

    /**
     * 余额退款（订单取消 / 补偿回款）：幂等同上
     */
    @PostMapping("/account/refund")
    Result<Boolean> refundBalance(@RequestParam("userId") Long userId,
                                  @RequestParam("amount") BigDecimal amount,
                                  @RequestParam("type") String type,
                                  @RequestParam("sourceId") Long sourceId,
                                  @RequestParam("remark") String remark);
}