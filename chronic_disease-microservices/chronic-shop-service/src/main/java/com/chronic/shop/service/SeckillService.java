package com.chronic.shop.service;

import com.chronic.shop.entity.SeckillActivity;

import java.util.List;

/**
 * 秒杀服务：Lua 原子预扣 → RocketMQ 异步落库 → Redis 结果登记。
 *
 * <p>Redis 挂了走 fail-closed（秒杀接口直接报"活动暂不可用"）——
 * 与令牌黑名单的 fail-open 相反：库存正确性是强需求，宁可不可用不可超卖。</p>
 *
 * @author chronic
 */
public interface SeckillService {

    /** 活动列表（前端按 start/end 时间自行区分 进行中/未开始/已结束） */
    List<SeckillActivity> listActivities();

    /** 活动详情 */
    SeckillActivity getActivity(Long activityId);

    /**
     * 抢购：校验活动窗口 → <b>消费滑块验证票据（前置人机拦截，一次性）</b>
     *       → Lua 原子预扣 → 发 MQ 排队落库。
     *
     * @param ticket 滑块验证通过后签发的一次性票据（SeckillCaptchaService.verify 签发）
     * @return 排队提示文案（结果通过 {@link #getResult} 轮询）
     */
    String seckill(Long userId, Long activityId, String ticket);

    /**
     * 消费者落库：DB 原子扣名额 → 建秒杀单（余额同步支付）→ 结果写 Redis。
     * 幂等：requestId = SK-{activityId}-{userId}，MQ 重投安全。
     */
    void processSeckill(Long activityId, Long userId, String requestId);

    /**
     * 查询抢购结果：null=处理中；"SUCCESS:{orderNo}"；"FAILED:{原因}"
     */
    String getResult(Long userId, Long activityId);

    /** 把 Redis 预扣的名额还回去（落库业务失败时调用；Lua INCRBY + SREM 原子执行） */
    void restock(Long activityId, Long userId);

    /** 管理端改活动（上下架/调时间）后失效活动缓存：抢购入口读缓存校验窗口，不再逐请求查 DB */
    void evictActivityCache(Long activityId);
}
