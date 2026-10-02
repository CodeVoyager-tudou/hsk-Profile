package com.chronic.shop.service;

import com.chronic.shop.mapper.SeckillActivityMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;

/**
 * 秒杀名额的「Redis 回补 / 名额释放」。
 *
 * <h3>为什么单独成一个组件（而不是塞进 SeckillService）</h3>
 * 「订单关掉要释放名额」发生在 {@code ShopOrderServiceImpl}（超时关单、用户取消、管理端退款），
 * 而 {@code SeckillServiceImpl} 又需要 {@code ShopOrderService} 来启动支付倒计时 ——
 * 两个服务互相依赖就成环。把「名额释放」这个叶子能力抽出来单独成类，依赖方向变成单向：
 * <pre>
 *   ShopOrderServiceImpl ──▶ SeckillSlotService
 *   SeckillServiceImpl   ──▶ SeckillSlotService
 * </pre>
 * 同时 Redis 的键名前缀与回补脚本也只有这一份，不会两处各写一套漂移。
 *
 * <h3>两种方法的差别（别混用）</h3>
 * <ul>
 *   <li>{@link #restockRedis}：只动 Redis。用于<b>抢购落库失败</b>——那时 DB 的
 *       {@code sold_count} 已随事务回滚自动撤销，只剩 Redis 预扣需要补回。</li>
 *   <li>{@link #releaseSlot}：DB 与 Redis 一起退。用于<b>秒杀单被关单/取消</b>——
 *       那时建单事务早已提交，{@code sold_count} 是真实占用，必须显式减回去。</li>
 * </ul>
 *
 * @author chronic
 */
@Slf4j
@Component
public class SeckillSlotService {

    /** 活动剩余名额（Redis 侧） */
    public static final String STOCK_KEY_PREFIX = "seckill:stock:";
    /** 已抢购用户集合（Lua 的 SISMEMBER 判定"一人一单"） */
    public static final String USERS_KEY_PREFIX = "seckill:users:";

    /** Lua：回补名额（与预扣对称，同样原子）。INCRBY 名额 + SREM 用户 */
    private static final String RESTOCK_LUA =
            "redis.call('INCRBY', KEYS[1], ARGV[2]) " +
            "redis.call('SREM', KEYS[2], ARGV[1]) " +
            "return 1";

    private final RedissonClient redissonClient;
    private final SeckillActivityMapper seckillActivityMapper;

    public SeckillSlotService(RedissonClient redissonClient, SeckillActivityMapper seckillActivityMapper) {
        this.redissonClient = redissonClient;
        this.seckillActivityMapper = seckillActivityMapper;
    }

    /**
     * 只回补 Redis 名额（DB 名额由事务回滚负责）。
     * 失败只告警不回滚：对账任务会比对 Redis 与 DB 差额，方向是少卖不超卖。
     */
    public void restockRedis(Long activityId, Long userId) {
        try {
            evalRestock(activityId, userId);
            log.info("秒杀名额已回补(Redis): activityId={}, userId={}", activityId, userId);
        } catch (Exception e) {
            log.error("秒杀名额回补失败，需对账: activityId={}, userId={}", activityId, userId, e);
        }
    }

    /**
     * 释放一个已被占用的名额：DB {@code sold_count - 1} + Redis 库存回补 + 把该用户
     * 从"已抢购"集合移除（于是他可以再抢一次）。
     *
     * <p><b>两段的时序是刻意分开的</b>：DB 那半立刻执行（加入调用方所在事务，随事务一起提交/回滚）；
     * Redis 那半注册到事务提交后执行——否则事务若在之后回滚，DB 名额退回、Redis 名额却已加回，
     * Redis 比 DB 多出一个名额就可能超卖。无事务上下文（单测）时立即执行。</p>
     *
     * <p>调用方是"订单已被关单/取消"的确定路径；失败只告警：少回补是"少卖"方向安全，
     * 反过来多回补会造成超卖，所以绝不在不确定时调用。</p>
     */
    public void releaseSlot(Long activityId, Long userId) {
        if (activityId == null) {
            return;
        }
        int rolled;
        try {
            rolled = seckillActivityMapper.decreaseSold(activityId, 1);
        } catch (Exception e) {
            log.error("秒杀名额回退失败（DB），需对账: activityId={}, userId={}", activityId, userId, e);
            return;
        }
        if (rolled == 0) {
            // sold_count 已为 0：说明本次名额从未真正占用（并发下重复释放），不动 Redis 更安全
            log.warn("秒杀名额回退未生效（sold_count 已为 0？）: activityId={}, userId={}", activityId, userId);
            return;
        }
        afterCommit(() -> restockRedis(activityId, userId));
        log.info("秒杀名额已释放（关单/取消）: activityId={}, userId={}", activityId, userId);
    }

    /** 有事务则注册到提交后执行，否则立即执行（与 ShopOrderServiceImpl 的 afterCommit 同语义） */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void evalRestock(Long activityId, Long userId) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        script.eval(RScript.Mode.READ_WRITE, RESTOCK_LUA, RScript.ReturnType.INTEGER,
                Arrays.asList(STOCK_KEY_PREFIX + activityId, USERS_KEY_PREFIX + activityId),
                String.valueOf(userId), "1");
    }
}
