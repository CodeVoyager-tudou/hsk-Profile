package com.chronic.shop.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chronic.shop.entity.SeckillActivity;
import com.chronic.shop.mapper.SeckillActivityMapper;
import com.chronic.shop.service.impl.SeckillServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀两个配套任务：名额预热 + Redis/DB 对账。
 *
 * <p>用 @Scheduled 而非 xxl-job，理由与补偿任务相同：这是<b>自愈逻辑</b>，
 * 不依赖调度控制台配置，服务一起来就必须生效。</p>
 *
 * @author chronic
 */
@Slf4j
@Component
public class SeckillJobs {

    private final SeckillActivityMapper seckillActivityMapper;
    private final SeckillServiceImpl seckillService;
    private final MeterRegistry meterRegistry;

    public SeckillJobs(SeckillActivityMapper seckillActivityMapper,
                       SeckillServiceImpl seckillService,
                       MeterRegistry meterRegistry) {
        this.seckillActivityMapper = seckillActivityMapper;
        this.seckillService = seckillService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 名额预热（每 30 秒）：把进行中/即将开始活动的总名额写进 Redis。
     * SETNX 语义（setIfAbsent）：只填空位，绝不覆盖运行中的扣减状态；
     * Redis 不可达时本轮放弃，下一轮再试（抢购入口有 fail-closed 兜底）。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void warmupStocks() {
        List<SeckillActivity> activities = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, 1)
                        .gt(SeckillActivity::getEndTime, LocalDateTime.now()));
        for (SeckillActivity activity : activities) {
            try {
                boolean firstWrite = seckillService.warmupStock(activity.getId(), activity.getTotalStock());
                if (firstWrite) {
                    // 名额首次入 Redis：顺手发一条可丢弃的预热消息，提前触发 topic 创建与消费路由更新，
                    // 消除"首条业务消息因 topic 未建而丢失"的窗口
                    seckillService.publishWarmupEvent(activity.getId());
                }
                // 活动信息缓存随预热每轮覆盖刷新：抢购入口的窗口校验读缓存，不再逐请求查 DB
                seckillService.cacheActivity(activity);
            } catch (Exception e) {
                log.warn("秒杀名额预热失败（Redis 异常，下轮重试）: activityId={}", activity.getId(), e);
            }
        }
    }

    /**
     * 对账（每 60 秒）：进行中活动的 Redis 剩余名额 vs DB(total - sold)。
     * 出现偏差打告警指标与日志 —— 偏差方向为正（Redis 多）意味着可能超卖，
     * 为负（Redis 少）意味着少卖，两者都值得人工看一眼，但都不阻塞线上。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void reconcileStocks() {
        List<SeckillActivity> activities = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, 1)
                        .le(SeckillActivity::getStartTime, LocalDateTime.now())
                        .gt(SeckillActivity::getEndTime, LocalDateTime.now()));
        for (SeckillActivity activity : activities) {
            Long redisStock = seckillService.readRedisStock(activity.getId());
            if (redisStock == null) {
                continue;
            }
            long dbStock = activity.getTotalStock() - activity.getSoldCount();
            if (redisStock != dbStock) {
                meterRegistry.counter("chronic.seckill.reconcile.mismatch",
                        "activityId", String.valueOf(activity.getId())).increment();
                log.warn("秒杀库存对账偏差: activityId={}, redis={}, db={}（差值={}）",
                        activity.getId(), redisStock, dbStock, redisStock - dbStock);
            }
        }
    }
}
