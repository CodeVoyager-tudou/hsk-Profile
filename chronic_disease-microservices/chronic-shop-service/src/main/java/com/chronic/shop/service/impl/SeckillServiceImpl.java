package com.chronic.shop.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.chronic.common.exception.BusinessException;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.entity.SeckillActivity;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.mapper.MedicineMapper;
import com.chronic.shop.mapper.SeckillActivityMapper;
import com.chronic.shop.mapper.ShopOrderMapper;
import com.chronic.shop.service.SeckillCaptchaService;
import com.chronic.shop.service.SeckillService;
import com.chronic.shop.service.SeckillSlotService;
import com.chronic.shop.service.ShopOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.chronic.shop.service.SeckillSlotService.STOCK_KEY_PREFIX;
import static com.chronic.shop.service.SeckillSlotService.USERS_KEY_PREFIX;

/**
 * 秒杀服务实现 —— Redis+Lua 预扣 + MQ 异步落库 + 结果登记。
 *
 * <h3>【小白先看：为什么要绕这么一大圈】</h3>
 * <pre>
 *   普通购买：MySQL 原子扣库存就够（量小，SQL 是权威，简单可靠）。
 *   秒杀瞬时流量可能是平时的百倍，MySQL 行锁会让所有请求在一条库存行上排队，
 *   数据库直接被拖垮 —— 所以把"挡人"挪到 Redis：
 *
 *     请求 → Lua 脚本（Redis 单线程执行，天生原子）：
 *             ①SISMEMBER 查"是否已抢过" ②GET 库存够不够 ③DECRBY 预扣 + SADD 记用户
 *           → 99% 的请求在这里就被挡掉，MySQL 只接收"预扣成功"的那一小撮
 *           → 预扣成功发 RocketMQ → 消费者慢慢落库（扣名额、建待支付单）
 * </pre>
 *
 * <h3>【抢到 ≠ 已付款：名额怎么释放】</h3>
 * 秒杀单落成的是<b>待支付现金单</b>（CASH + PENDING），建单后由
 * {@link com.chronic.shop.service.ShopOrderService#beginPaymentAfterCreated} 挂上
 * 30 分钟支付超时关单，用户在收银台付款才转 PAID。因此名额的三种去向是：
 * <ul>
 *   <li>落库<b>业务失败</b>（已抢完 / 不在活动窗口）→ 事务回滚撤销 DB 名额，
 *       Redis 预扣由 {@link com.chronic.shop.service.SeckillSlotService#restockRedis} 补回；</li>
 *   <li>落库<b>系统异常</b> → 名额不回补：少卖可接受，多放导致超卖不可接受；</li>
 *   <li>建单成功但用户<b>不付款</b> → 30 分钟后超时关单，由 {@code ShopOrderServiceImpl}
 *       按订单上的 {@code seckill_activity_id} 释放名额
 *       （DB + Redis，见 {@link com.chronic.shop.service.SeckillSlotService#releaseSlot}）。</li>
 * </ul>
 *
 * <h3>【Redis 挂了怎么办】</h3>
 * fail-closed：秒杀接口直接报"活动暂不可用"。库存正确性是强需求，
 * 与登出黑名单的"降级放行"（fail-open）取向相反——按业务性质选 fail 方向。
 *
 * @author chronic
 */
@Slf4j
@Service
public class SeckillServiceImpl implements SeckillService {

    /** Lua：一人一单 + 库存校验 + 原子预扣。返回 0-成功 1-重复抢购 2-库存不足/未预热 */
    private static final String SECKILL_LUA =
            "if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 1 end " +
            "local stock = tonumber(redis.call('GET', KEYS[1])) " +
            "if stock == nil then return 2 end " +
            "if stock < tonumber(ARGV[2]) then return 2 end " +
            "redis.call('DECRBY', KEYS[1], ARGV[2]) " +
            "redis.call('SADD', KEYS[2], ARGV[1]) " +
            "return 0";

    private static final String RESULT_KEY_PREFIX = "seckill:result:";
    /** 抢购结果保留 1 小时，前端轮询窗口足够 */
    private static final long RESULT_TTL_SECONDS = 3_600L;
    /** 活动信息缓存（入口窗口校验用）：预热任务 30s 一轮覆盖刷新并续命，停摆后最多 45s 自动回源 DB */
    private static final String ACTIVITY_CACHE_KEY_PREFIX = "seckill:activity:";
    private static final long ACTIVITY_CACHE_TTL_SECONDS = 45L;
    /** 命中"已抢购"时返回的文案（Lua 返回值 1 对应的分支） */
    private static final String ALREADY_GRABBED_MSG = "您已抢购过该活动";

    private final SeckillActivityMapper seckillActivityMapper;
    private final ShopOrderMapper shopOrderMapper;
    private final MedicineMapper medicineMapper;
    private final RedissonClient redissonClient;
    private final SeckillCaptchaService captchaService;
    /** 名额回补与释放：Redis 键名/脚本与关单侧共用一份（见 {@link SeckillSlotService}） */
    private final SeckillSlotService seckillSlotService;
    /**
     * 建单后启动支付（现金单挂 30 分钟超时关单延迟消息 / 模拟渠道即时确认）。
     * 与普通单走同一条路径，避免两处各写一套支付策略而漂移。
     */
    private final ShopOrderService shopOrderService;
    /** RocketMQTemplate 可能不存在（未接 MQ 的部署）：抢购入口直接降级为不可用 */
    private final Optional<org.apache.rocketmq.spring.core.RocketMQTemplate> rocketMQTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${chronic.shop.seckill.topic:shop-seckill-topic}")
    private String topic;

    public SeckillServiceImpl(SeckillActivityMapper seckillActivityMapper,
                              ShopOrderMapper shopOrderMapper,
                              MedicineMapper medicineMapper,
                              RedissonClient redissonClient,
                              SeckillCaptchaService captchaService,
                              SeckillSlotService seckillSlotService,
                              ShopOrderService shopOrderService,
                              Optional<org.apache.rocketmq.spring.core.RocketMQTemplate> rocketMQTemplate,
                              PlatformTransactionManager transactionManager) {
        this.seckillActivityMapper = seckillActivityMapper;
        this.shopOrderMapper = shopOrderMapper;
        this.medicineMapper = medicineMapper;
        this.redissonClient = redissonClient;
        this.captchaService = captchaService;
        this.seckillSlotService = seckillSlotService;
        this.shopOrderService = shopOrderService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<SeckillActivity> listActivities() {
        List<SeckillActivity> activities = seckillActivityMapper.selectList(null);
        enrichMedicineInfo(activities);
        return activities;
    }

    /**
     * 回填药品名与图片 URL（实体的非表字段）：秒杀卡片要显示"这个药自己的图片"，
     * 必须和商城列表用同一份数据（同名/同上传图），否则两页图对不上。
     * 一次批量查询，不逐条查库。
     */
    private void enrichMedicineInfo(List<SeckillActivity> activities) {
        if (activities == null || activities.isEmpty()) {
            return;
        }
        List<Long> medicineIds = activities.stream()
                .map(SeckillActivity::getMedicineId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (medicineIds.isEmpty()) {
            return;
        }
        Map<Long, Medicine> byId = medicineMapper.selectBatchIds(medicineIds).stream()
                .collect(Collectors.toMap(Medicine::getId, Function.identity(), (a, b) -> a));
        for (SeckillActivity activity : activities) {
            Medicine medicine = byId.get(activity.getMedicineId());
            if (medicine != null) {
                activity.setMedicineName(medicine.getName());
                activity.setMedicineImageUrl(medicine.getImageUrl());
            }
        }
    }

    @Override
    public SeckillActivity getActivity(Long activityId) {
        return seckillActivityMapper.selectById(activityId);
    }

    @Override
    public String seckill(Long userId, Long activityId, String ticket) {
        // 0. 前置人机拦截：一次性滑块票据，缺票/错票/重放一律 fail-closed。
        //    放在最前面——最便宜的检查最先做，让脚本在消耗活动窗口/Lua/MQ 资源之前就被挡掉
        if (!captchaService.consumeTicket(userId, ticket)) {
            throw new BusinessException(400, "请先完成滑块验证");
        }
        // 活动窗口校验：优先读预热缓存（高频入口不查 MySQL），未命中回源 DB 并回填
        requireOnWindowCached(activityId);
        String requestId = seckillRequestId(activityId, userId);
        // 1. Lua 原子预扣：Redis 单线程执行，"查重 + 查库存 + 扣减"三步不可分割
        Long code = evalLua(SECKILL_LUA, activityId, userId);
        if (code == 1L) {
            throw new BusinessException(ALREADY_GRABBED_MSG);
        }
        if (code == 2L) {
            throw new BusinessException("已抢完，下次早点来");
        }
        // 2. 预扣成功 → 发 MQ 排队。发送失败（Broker 不可达）→ 名额还回去再报错
        if (rocketMQTemplate.isEmpty()) {
            restock(activityId, userId);
            throw new BusinessException("活动通道不可用，请稍后再试");
        }
        try {
            String payload = JSONUtil.toJsonStr(new SeckillMessage(userId, activityId, requestId));
            rocketMQTemplate.get().syncSend(topic, payload);
        } catch (Exception e) {
            log.error("秒杀消息发送失败，回补名额: activityId={}, userId={}", activityId, userId, e);
            restock(activityId, userId);
            throw new BusinessException("排队失败，请重试");
        }
        log.info("秒杀预扣成功已排队: activityId={}, userId={}, requestId={}", activityId, userId, requestId);
        return "已进入排队，结果稍后可在本页查看";
    }

    @Override
    public void processSeckill(Long activityId, Long userId, String requestId) {
        // 幂等：MQ 至少一次投递，同一 requestId 的秒杀单已存在时直接补登结果
        ShopOrder existing = shopOrderMapper.selectByRequestId(userId, requestId);
        if (existing != null) {
            markSuccessResult(activityId, userId, existing.getOrderNo(), existing.getId());
            return;
        }
        ShopOrder order;
        try {
            order = transactionTemplate.execute(status ->
                    createSeckillOrderInTx(activityId, userId, requestId));
        } catch (DuplicateOrderException e) {
            // MQ 重投撞 requestId：按原单成功处理（幂等），名额本次没有多扣
            ShopOrder dup = shopOrderMapper.selectByRequestId(userId, requestId);
            markSuccessResult(activityId, userId, e.orderNo, dup == null ? null : dup.getId());
            return;
        } catch (BusinessException e) {
            // 业务失败（已抢完 / 不在活动窗口）：事务已回滚撤销 DB 名额，Redis 名额回补
            seckillSlotService.restockRedis(activityId, userId);
            markResult(activityId, userId, "FAILED:" + e.getMessage());
            return;
        } catch (Exception e) {
            log.error("秒杀落库系统异常: activityId={}, userId={}", activityId, userId, e);
            markResult(activityId, userId, "FAILED:系统繁忙，请稍后重试");
            return;
        }
        // 事务已提交才启动支付：延迟消息若在事务内发出，一旦回滚就成了指向不存在订单的野消息
        markSuccessResult(activityId, userId, order.getOrderNo(), order.getId());
        shopOrderService.beginPaymentAfterCreated(order);
        log.info("秒杀落库成功: activityId={}, userId={}, orderNo={}, orderId={}",
                activityId, userId, order.getOrderNo(), order.getId());
    }

    /**
     * 登记抢购成功。payload 形如 {@code SUCCESS:<orderNo>:<orderId>}。
     *
     * <p>带上 orderId 是因为前端抢购成功要直接跳收银台（路由 {@code /shop/cashier/:orderId}），
     * 只给订单号还得再查一次接口。失败侧仍是 {@code FAILED:<原因>}，
     * 前缀 {@code SUCCESS:} 不变，老版本前端/脚本只做前缀判断，向后兼容。</p>
     */
    private void markSuccessResult(Long activityId, Long userId, String orderNo, Long orderId) {
        markResult(activityId, userId,
                "SUCCESS:" + orderNo + (orderId == null ? "" : ":" + orderId));
    }

    /**
     * 秒杀落库（在 TransactionTemplate 事务内执行）：DB 原子扣名额 → 建单(PENDING, 现金支付)。
     *
     * <p>这里刻意<b>不</b>扣款：秒杀单与普通现金单同构，建单后由
     * {@link ShopOrderService#beginPaymentAfterCreated} 挂上支付倒计时，
     * 用户在收银台（30 分钟窗口）完成支付。于是"抢到"与"付款"解耦：</p>
     * <ul>
     *   <li>余额不足不再是秒杀失败的原因——抢到就是占住名额，付不付在收银台决定；</li>
     *   <li>未支付的单超时关单时会释放名额（{@code ShopOrderServiceImpl} 按
     *       {@code seckillActivityId} 识别，见 {@link SeckillSlotService#releaseSlot}）。</li>
     * </ul>
     *
     * @return 建好的订单（调用方需要 id 做收银台跳转与支付倒计时）
     */
    private ShopOrder createSeckillOrderInTx(Long activityId, Long userId, String requestId) {
        SeckillActivity activity = requireOnWindow(activityId);
        // DB 名额原子扣减（Redis 预扣后的权威兜底）：0 行 = 名额已被抢完
        if (seckillActivityMapper.increaseSold(activityId, 1) == 0) {
            throw new BusinessException("已抢完");
        }
        ShopOrder order = new ShopOrder();
        order.setOrderNo(IdUtil.fastSimpleUUID());
        order.setRequestId(requestId);
        order.setUserId(userId);
        order.setMedicineId(activity.getMedicineId());
        order.setMedicineName(activity.getTitle());
        order.setQuantity(1);
        order.setUnitPrice(activity.getSeckillPrice());
        order.setTotalAmount(activity.getSeckillPrice());
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPointsEarned(0);
        order.setPointsStatus(0);
        // 现金支付 + 待支付：收银台的 payPendingOrder 只接受 CASH 的 PENDING 单，
        // 非 CASH 会被直接拒绝（"该订单不支持收银台支付"）
        order.setPayType("CASH");
        order.setStatus("PENDING");
        // 溯源：关单/取消时据此释放秒杀名额，缺这列就会每次未支付都永久吃掉一个名额
        order.setSeckillActivityId(activityId);
        try {
            shopOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // requestId 唯一键撞车 = 这单已经建过（MQ 重投），名额本次没有多占
            ShopOrder dup = shopOrderMapper.selectByRequestId(userId, requestId);
            throw new DuplicateOrderException(dup == null ? "UNKNOWN" : dup.getOrderNo());
        }
        return order;
    }

    @Override
    public String getResult(Long userId, Long activityId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(RESULT_KEY_PREFIX + activityId + ":" + userId,
                    StringCodec.INSTANCE);
            return bucket.get();
        } catch (Exception e) {
            log.warn("秒杀结果查询失败（Redis 异常）: activityId={}, userId={}", activityId, userId, e);
            return null;
        }
    }

    @Override
    public void restock(Long activityId, Long userId) {
        // 只回补 Redis：调用方都是"事务已回滚"的路径，DB 名额已随回滚撤销
        seckillSlotService.restockRedis(activityId, userId);
    }

    /** 预热：活动名额写入 Redis（SETNX，绝不覆盖运行中的扣减状态）。
     *  @return true = 本次为首次写入（topic 可能尚未创建，调用方应发一条预热消息踩通消费链路） */
    public boolean warmupStock(Long activityId, int totalStock) {
        RBucket<String> bucket = redissonClient.getBucket(STOCK_KEY_PREFIX + activityId, StringCodec.INSTANCE);
        return bucket.setIfAbsent(String.valueOf(totalStock));
    }

    /**
     * 预热消息：名额首次入 Redis 时发一条可丢弃的 WARMUP 消息——
     * 让 broker 此刻自动创建 topic、消费者完成路由更新，把"首条业务消息丢失"窗口
     * 压到活动开始之前（业务消息到达时 topic 与消费路由早已就绪）。
     * 发送失败只告警，下一轮预热（30s）重试。
     */
    public void publishWarmupEvent(Long activityId) {
        if (rocketMQTemplate.isEmpty()) {
            return;
        }
        try {
            rocketMQTemplate.get().syncSend(topic, "{\"type\":\"WARMUP\",\"activityId\":" + activityId + "}");
            log.info("秒杀预热消息已发送（触发 topic 创建/路由更新）: activityId={}", activityId);
        } catch (Exception e) {
            log.warn("秒杀预热消息发送失败（下轮预热重试）: activityId={}", activityId, e);
        }
    }

    /** 对账：返回 Redis 剩余名额（读不到返回 null），由对账任务与 DB(总-已售) 比对 */
    public Long readRedisStock(Long activityId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(STOCK_KEY_PREFIX + activityId, StringCodec.INSTANCE);
            String value = bucket.get();
            return value == null ? null : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("秒杀 Redis 库存读取失败: activityId={}", activityId, e);
            return null;
        }
    }

    /**
     * 执行 Lua 脚本（预扣/回补共用）。包级可见仅为测试桩入。
     * Redis 异常 fail-closed：宁可抢不到不可超卖。
     */
    Long evalLua(String lua, Long activityId, Long userId) {
        try {
            RScript script = redissonClient.getScript(StringCodec.INSTANCE);
            return script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.INTEGER,
                    Arrays.asList(STOCK_KEY_PREFIX + activityId, USERS_KEY_PREFIX + activityId),
                    String.valueOf(userId), "1");
        } catch (Exception e) {
            // Redis 异常 fail-closed：宁可抢不到不可超卖
            log.error("秒杀 Lua 执行失败（Redis 异常）: activityId={}, userId={}", activityId, userId, e);
            throw new BusinessException(503, "活动太火爆，请稍后再试");
        }
    }

    private void markResult(Long activityId, Long userId, String value) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(RESULT_KEY_PREFIX + activityId + ":" + userId,
                    StringCodec.INSTANCE);
            bucket.set(value, RESULT_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("秒杀结果写入失败（Redis 异常）: activityId={}, userId={}, value={}",
                    activityId, userId, value, e);
        }
    }

    /** 活动窗口校验（DB 权威版）：落库消费者用；高频抢购入口走 {@link #requireOnWindowCached} */
    private SeckillActivity requireOnWindow(Long activityId) {
        SeckillActivity activity = seckillActivityMapper.selectById(activityId);
        validateOnWindow(activity);
        return activity;
    }

    /** 活动窗口校验（纯检查）：状态上架 + 当前时间在起止区间内（到点即生效，不等预热周期） */
    private void validateOnWindow(SeckillActivity activity) {
        if (activity == null || activity.getStatus() == null || activity.getStatus() != 1) {
            throw new BusinessException("活动不存在或已下架");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            throw new BusinessException("活动尚未开始");
        }
        if (now.isAfter(activity.getEndTime())) {
            throw new BusinessException("活动已结束");
        }
    }

    /**
     * 抢购入口的活动窗口校验（缓存优先，对齐"抢购链路不碰 MySQL"）：
     * 命中 → 直接用缓存的起止时间实时判断；未命中（首次/过期/Redis 抖动）→ 回源 DB 并回填。
     * 与库存 Lua 的 fail-closed 不同，这里缓存异常按"未命中"降级——活动信息不承载
     * 正确性（DB 才是权威），多查一次库只是慢，不会错。
     */
    private void requireOnWindowCached(Long activityId) {
        SeckillActivity cached = readCachedActivity(activityId);
        if (cached != null) {
            validateOnWindow(cached);
            return;
        }
        cacheActivity(requireOnWindow(activityId));
    }

    /** 读活动缓存：Redis 异常按未命中处理（回源 DB），不让缓存故障放大成抢购不可用 */
    SeckillActivity readCachedActivity(Long activityId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(ACTIVITY_CACHE_KEY_PREFIX + activityId, StringCodec.INSTANCE);
            String json = bucket.get();
            return json == null ? null : JSONUtil.toBean(json, SeckillActivity.class);
        } catch (Exception e) {
            log.warn("秒杀活动缓存读取失败（回源 DB）: activityId={}", activityId, e);
            return null;
        }
    }

    /** 活动信息入缓存：预热任务每轮覆盖刷新 + 入口回源回填；失败只告警，不影响主流程 */
    public void cacheActivity(SeckillActivity activity) {
        if (activity == null || activity.getId() == null) {
            return;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(ACTIVITY_CACHE_KEY_PREFIX + activity.getId(), StringCodec.INSTANCE);
            bucket.set(JSONUtil.toJsonStr(activity), ACTIVITY_CACHE_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("秒杀活动缓存写入失败: activityId={}", activity.getId(), e);
        }
    }

    /** 管理端改活动（上下架/调时间）后立即失效缓存：把"最多 30s 生效"压成"立即生效" */
    @Override
    public void evictActivityCache(Long activityId) {
        try {
            redissonClient.getBucket(ACTIVITY_CACHE_KEY_PREFIX + activityId, StringCodec.INSTANCE).delete();
            log.info("秒杀活动缓存已失效: activityId={}", activityId);
        } catch (Exception e) {
            log.warn("秒杀活动缓存删除失败（依赖预热刷新/TTL 过期自愈）: activityId={}", activityId, e);
        }
    }

    /**
     * 抢购请求的幂等键：{@code SK-<activityId>-<userId>-<本次抢购的随机后缀>}。
     *
     * <p><b>后缀不可省。</b>订单唯一约束是 (user_id, request_id)，MQ 的"至少一次投递"靠
     * "同一条消息体里始终带着同一个 requestId"实现幂等——后缀随消息体一起传递，
     * 重投仍是同一个值，语义不变。</p>
     *
     * <p>但键不能写成固定的 {@code SK-<activityId>-<userId>}：秒杀单是待支付单，
     * 超时关单后会释放名额（用户可以从已抢购集合里移除、可以再抢），
     * 那时第二次抢购会撞上第一张（已取消）订单的唯一键，被误判成"MQ 重投"而不再建单，
     * 结果指向一张已取消的订单。</p>
     */
    public static String seckillRequestId(Long activityId, Long userId) {
        return "SK-" + activityId + "-" + userId + "-" + IdUtil.fastSimpleUUID();
    }

    /** requestId 撞键（MQ 重投幂等命中）的内部标记，携带原订单号 */
    private static class DuplicateOrderException extends RuntimeException {
        private final String orderNo;

        DuplicateOrderException(String orderNo) {
            this.orderNo = orderNo;
        }
    }

    /** 秒杀排队消息体 */
    public static class SeckillMessage {
        public Long userId;
        public Long activityId;
        public String requestId;

        public SeckillMessage() {
        }

        public SeckillMessage(Long userId, Long activityId, String requestId) {
            this.userId = userId;
            this.activityId = activityId;
            this.requestId = requestId;
        }
    }

}
