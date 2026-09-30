package com.chronic.shop.job;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.CompensationTask;
import com.chronic.shop.feign.PointsFeignClient;
import com.chronic.shop.mapper.CompensationTaskMapper;
import com.chronic.shop.mapper.ShopOrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨服务补偿重试任务 —— 「对不上账时，谁来补」。
 *
 * <h3>【小白先看：为什么需要"补偿"】</h3>
 * 一次下单往往要同时改两个地方的数据：
 * <pre>
 *   本服务的库：创建订单、扣库存
 *   积分服务：  扣积分 / 加积分
 * </pre>
 * 这两个库不在同一个数据库事务里，所以做不到"要么都成功、要么都失败"。
 * 典型困境：本地事务已经回滚（订单没了），但积分服务的扣分已经生效了。
 *
 * 解决办法叫 <b>补偿（compensation）</b>：把"欠这一笔"记到一张表里
 * （compensation_task，叫补偿台账），然后由本类定时扫描、自动重试，
 * 直到成功为止；重试多次仍失败的，标记 FAILED 并报警，等人来处理。
 *
 * 类比：银行转账失败时不会凭空丢钱，而是生成一笔"差错记录"待人工/自动冲正。
 *
 * <h3>【本类的行为】</h3>
 * <ul>
 *   <li>每 5 分钟跑一轮，只处理"到了下次重试时间"的 PENDING 记录；</li>
 *   <li>失败按指数退避重试（1、2、4、8… 分钟，上限 60 分钟），最多 5 次；</li>
 *   <li>超过上限 -> 状态置 FAILED + ERROR 日志 + 指标 chronic.compensation.failed
 *       （可据此配告警规则）；</li>
 *   <li><b>幂等</b>：积分服务侧以「业务类型 + 业务ID」建唯一键，
 *       因此重复重试不会重复加/减分。</li>
 * </ul>
 *
 * <h3>【J-04 修复说明：为什么 FAILED 的记录不会"堵死"后续补偿】</h3>
 * 原先 compensation_task 的唯一键是 (biz_type, biz_id)，且**不区分状态**。
 * 这意味着某个任务一旦变成 FAILED，那一行仍然占着唯一键；
 * 该订单之后若再需要补偿，插入会撞唯一键而失败（且当时只打了一条 warn 日志就吞掉了），
 * 结果就是这笔积分<b>永久退不回来</b>。
 * 现在唯一键改成 (biz_type, biz_id, active_flag)，其中 active_flag 是数据库生成列，
 * <b>只有 PENDING 状态才为 1，其余状态为 NULL</b>；而 MySQL 的唯一索引允许出现多个 NULL，
 * 于是"在途任务只有一条"（防重复建账）和"历史失败记录不再挡路"两个目标可以同时满足。
 *
 * @author chronic
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationRetryJob {

    private static final int MAX_RETRY = 5;
    private static final int BATCH_LIMIT = 50;

    private final CompensationTaskMapper compensationTaskMapper;
    private final PointsFeignClient pointsFeignClient;
    private final ShopOrderMapper shopOrderMapper;
    private final MeterRegistry meterRegistry;

    /** 每 5 分钟跑一轮；只处理到了下次重试时间的任务 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 180_000)
    public void retryCompensations() {
        List<CompensationTask> tasks = compensationTaskMapper.selectList(new LambdaQueryWrapper<CompensationTask>()
                .eq(CompensationTask::getStatus, "PENDING")
                .le(CompensationTask::getNextRetryTime, LocalDateTime.now())
                .orderByAsc(CompensationTask::getId)
                .last("LIMIT " + BATCH_LIMIT));
        if (tasks.isEmpty()) {
            return;
        }
        log.info("补偿任务开始，待处理 {} 笔", tasks.size());
        int success = 0;
        for (CompensationTask task : tasks) {
            if (execute(task)) {
                success++;
            }
        }
        log.info("补偿任务结束：成功 {}/{}", success, tasks.size());
    }

    /** 执行一笔补偿（定时任务与"管理端手动重试"共用；返回是否成功） */
    public boolean execute(CompensationTask task) {
        int points = readPoints(task.getPayload());
        try {
            Result<Boolean> result;
            switch (task.getBizType()) {
                case "ORDER_POINTS_REVOKE":
                    result = pointsFeignClient.deductPoints(task.getUserId(), points, "ORDER_CANCEL_REVOKE",
                            task.getBizId(), "取消订单回收积分(补偿重试)");
                    break;
                case "POINTS_REFUND":
                    result = pointsFeignClient.refundPoints(task.getUserId(), points, "POINTS_REFUND",
                            task.getBizId(), "兑换取消退回积分(补偿重试)");
                    break;
                case "ORDER_POINTS_GRANT":
                    result = pointsFeignClient.addPoints(task.getUserId(), points, "ORDER_PURCHASE",
                            task.getBizId(), "补发购买积分(补偿重试)");
                    break;
                case "POINTS_EXCHANGE_ROLLBACK":
                    // J-03：积分兑换时扣分结果不明（超时/网络异常）而本地事务已回滚，
                    // 此处按订单核对并退还可能已被扣走的积分。
                    // 积分流水以 (bizType, bizId) 幂等，重复退还会被唯一键挡住，不会重复加积分。
                    result = pointsFeignClient.refundPoints(task.getUserId(), points, "POINTS_EXCHANGE_ROLLBACK",
                            task.getBizId(), "积分兑换失败退还(补偿重试)");
                    break;
                case "BALANCE_REFUND":
                    // 余额订单取消时退款失败 → 定时重试退回余额（幂等：BALANCE_REFUND + orderId 唯一）
                    result = pointsFeignClient.refundBalance(task.getUserId(), readAmount(task.getPayload()),
                            "BALANCE_REFUND", task.getBizId(), "订单退款(补偿重试)");
                    break;
                case "BALANCE_PAY_ROLLBACK":
                    // 余额支付扣款结果不明且本地事务已回滚 → 先核对订单：若订单实际已提交为 PAID，
                    // 说明扣款是正确的，无需退款；否则退回余额（幂等：BALANCE_PAY_ROLLBACK + orderId 唯一）
                    result = compensateBalancePayRollback(task);
                    if (result == null) {
                        // 已核对无需退款，直接结案
                        markDone(task);
                        return true;
                    }
                    break;
                default:
                    markFailed(task, "未知的补偿类型: " + task.getBizType());
                    return false;
            }
            if (result != null && result.getCode() != null && result.getCode() == 200) {
                markDone(task);
                return true;
            }
            scheduleRetry(task, result == null ? "积分服务不可用" : result.getMessage());
            return false;
        } catch (Exception e) {
            scheduleRetry(task, e.getMessage());
            return false;
        }
    }

    private int readPoints(String payload) {
        try {
            return JSONUtil.parseObj(payload == null ? "{}" : payload).getInt("points", 0);
        } catch (Exception e) {
            log.warn("补偿参数解析失败，按 0 处理: {}", payload);
            return 0;
        }
    }

    /** 余额类补偿的 payload 形如 {"amount":"19.90"}，解析失败按 0 处理（退款 0 元幂等无害） */
    private java.math.BigDecimal readAmount(String payload) {
        try {
            String amount = JSONUtil.parseObj(payload == null ? "{}" : payload).getStr("amount");
            return amount == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(amount);
        } catch (Exception e) {
            log.warn("补偿金额解析失败，按 0 处理: {}", payload);
            return java.math.BigDecimal.ZERO;
        }
    }

    /**
     * BALANCE_PAY_ROLLBACK 的核对逻辑：余额扣款结果不明而本地事务已回滚后，
     * 唯一的歧义是「订单到底提交成功没有」——
     * <ul>
     *   <li>订单存在且 PAID：扣款是正确的（钱货两清），无需退款，返回 null 由调用方结案；</li>
     *   <li>订单不存在（本地事务确实回滚了）：退回余额，流水 (BALANCE_PAY_ROLLBACK, orderId) 幂等；</li>
     *   <li>订单存在但仍是 PENDING（理论不应出现）：按未支付处理，退回余额，订单留给超时关单回收。</li>
     * </ul>
     */
    private Result<Boolean> compensateBalancePayRollback(CompensationTask task) {
        com.chronic.shop.entity.ShopOrder order = shopOrderMapper.selectById(task.getBizId());
        if (order != null && "PAID".equals(order.getStatus())) {
            log.info("核对无需退款：订单已支付，扣款有效: bizId={}", task.getBizId());
            return null;
        }
        return pointsFeignClient.refundBalance(task.getUserId(), readAmount(task.getPayload()),
                "BALANCE_PAY_ROLLBACK", task.getBizId(), "余额支付失败退还(补偿重试)");
    }

    private void markDone(CompensationTask task) {
        compensationTaskMapper.update(null, new LambdaUpdateWrapper<CompensationTask>()
                .eq(CompensationTask::getId, task.getId())
                .set(CompensationTask::getStatus, "DONE")
                .set(CompensationTask::getLastError, null));
        log.info("补偿成功: bizType={}, bizId={}, userId={}", task.getBizType(), task.getBizId(), task.getUserId());
    }

    private void scheduleRetry(CompensationTask task, String error) {
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        String message = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        if (retry >= MAX_RETRY) {
            markFailed(task, message);
            return;
        }
        long delayMinutes = Math.min(60L, 1L << retry);
        compensationTaskMapper.update(null, new LambdaUpdateWrapper<CompensationTask>()
                .eq(CompensationTask::getId, task.getId())
                .set(CompensationTask::getRetryCount, retry)
                .set(CompensationTask::getLastError, message)
                .set(CompensationTask::getNextRetryTime, LocalDateTime.now().plusMinutes(delayMinutes)));
        log.warn("补偿失败，{} 分钟后重试（第 {} 次）: bizType={}, bizId={}, 原因={}",
                delayMinutes, retry, task.getBizType(), task.getBizId(), message);
    }

    private void markFailed(CompensationTask task, String error) {
        compensationTaskMapper.update(null, new LambdaUpdateWrapper<CompensationTask>()
                .eq(CompensationTask::getId, task.getId())
                .set(CompensationTask::getStatus, "FAILED")
                .set(CompensationTask::getLastError, error));
        meterRegistry.counter("chronic.compensation.failed", "bizType", task.getBizType()).increment();
        log.error("补偿超过重试上限，需人工介入！bizType={}, bizId={}, userId={}, 最后原因={}",
                task.getBizType(), task.getBizId(), task.getUserId(), error);
    }
}
