package com.chronic.shop.service.impl;

import com.chronic.common.exception.BusinessException;
import com.chronic.shop.entity.SeckillActivity;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.mapper.MedicineMapper;
import com.chronic.shop.mapper.SeckillActivityMapper;
import com.chronic.shop.mapper.ShopOrderMapper;
import com.chronic.shop.service.SeckillCaptchaService;
import com.chronic.shop.service.SeckillSlotService;
import com.chronic.shop.service.ShopOrderService;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 秒杀服务单测：Redis 交互通过 spy 桩掉 {@code evalLua}（Lua 脚本语义另由
 * 真实 Redis 集成测试 SeckillLuaIT 验证），重点验证「排队的分支走向」与「落库的分支走向」：
 * 预扣成功→排队、重复抢购/已抢完→拒绝、落库业务失败→回补 Redis 名额、
 * 落库系统异常→不回补（少卖方向）。
 *
 * <p>秒杀单落成的是「待支付现金单」（不扣款、PENDING），所以这里不再有
 * "余额不足/扣款结果不明"两条分支——付款与超时关单由收银台与
 * {@code ShopOrderServiceImpl} 负责，见 {@link SeckillSlotService}。</p>
 *
 * @author chronic
 */
@ExtendWith(MockitoExtension.class)
class SeckillServiceImplTest {

    private static final long ACTIVITY_ID = 7L;
    private static final long USER_ID = 1L;
    private static final String REQUEST_ID = "SK-7-1";

    @Mock
    private SeckillActivityMapper seckillActivityMapper;

    @Mock
    private ShopOrderMapper shopOrderMapper;

    @Mock
    private MedicineMapper medicineMapper;

    @Mock
    private SeckillSlotService seckillSlotService;

    @Mock
    private ShopOrderService shopOrderService;

    @Mock
    private SeckillCaptchaService captchaService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBucket<String> resultBucket;

    @Mock
    private PlatformTransactionManager transactionManager;

    private SeckillServiceImpl seckillService;

    @BeforeEach
    void setUp() {
        lenient().doReturn(resultBucket).when(redissonClient).getBucket(anyString(), any(Codec.class));
        TransactionStatus status = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(status);
        // 默认：滑块票据有效（票据/验证码服务的自身语义由 SeckillCaptchaServiceTest 单独覆盖）。
        // anyString() 刻意不匹配 null——"缺票据"场景要用默认 false 走前置拦截分支
        lenient().when(captchaService.consumeTicket(eq(USER_ID), anyString())).thenReturn(true);

        seckillService = spy(new SeckillServiceImpl(seckillActivityMapper, shopOrderMapper, medicineMapper,
                redissonClient, captchaService, seckillSlotService, shopOrderService,
                Optional.empty(), transactionManager));
        // 默认：Lua 脚本执行成功（预扣/回补都放行）；各用例按需覆盖
        lenient().doReturn(0L).when(seckillService).evalLua(anyString(), eq(ACTIVITY_ID), eq(USER_ID));
    }

    private SeckillActivity ongoingActivity() {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(ACTIVITY_ID);
        activity.setMedicineId(1L);
        activity.setTitle("秒杀活动");
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setTotalStock(50);
        activity.setSoldCount(0);
        activity.setStatus(1);
        activity.setStartTime(LocalDateTime.now().minusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        return activity;
    }

    private void stubOrderCreation() {
        when(seckillActivityMapper.selectById(ACTIVITY_ID)).thenReturn(ongoingActivity());
        when(seckillActivityMapper.increaseSold(ACTIVITY_ID, 1)).thenReturn(1);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenAnswer(invocation -> {
            ShopOrder order = invocation.getArgument(0);
            order.setId(99L); // 模拟数据库自增回填
            return 1;
        });
    }

    @Test
    void seckill_shouldReject_whenActivityNotStarted() {
        SeckillActivity activity = ongoingActivity();
        activity.setStartTime(LocalDateTime.now().plusHours(1));
        when(seckillActivityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.seckill(USER_ID, ACTIVITY_ID, "tk"));
        assertTrue(ex.getMessage().contains("尚未开始"));
        verify(seckillService, never()).evalLua(anyString(), anyLong(), anyLong());
    }

    @Test
    void seckill_shouldRejectBeforeEverything_whenTicketMissing() {
        // 前置拦截语义：缺票据连活动窗口都不查——最便宜的检查最先做
        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.seckill(USER_ID, ACTIVITY_ID, null));
        assertTrue(ex.getMessage().contains("滑块验证"));
        verify(seckillActivityMapper, never()).selectById(anyLong());
    }

    @Test
    void seckill_shouldReject_whenTicketInvalid() {
        when(captchaService.consumeTicket(eq(USER_ID), eq("bad-ticket"))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.seckill(USER_ID, ACTIVITY_ID, "bad-ticket"));
        assertTrue(ex.getMessage().contains("滑块验证"));
        verify(seckillActivityMapper, never()).selectById(anyLong());
    }

    @Test
    void seckill_shouldReject_whenAlreadyJoined() {
        when(seckillActivityMapper.selectById(ACTIVITY_ID)).thenReturn(ongoingActivity());
        doReturn(1L).when(seckillService).evalLua(anyString(), eq(ACTIVITY_ID), eq(USER_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.seckill(USER_ID, ACTIVITY_ID, "tk"));
        assertTrue(ex.getMessage().contains("已抢购过"));
    }

    @Test
    void seckill_shouldReject_whenSoldOut() {
        when(seckillActivityMapper.selectById(ACTIVITY_ID)).thenReturn(ongoingActivity());
        doReturn(2L).when(seckillService).evalLua(anyString(), eq(ACTIVITY_ID), eq(USER_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.seckill(USER_ID, ACTIVITY_ID, "tk"));
        assertTrue(ex.getMessage().contains("已抢完"));
    }

    @Test
    void processSeckill_shouldCreatePendingCashOrderAndMarkSuccess() {
        when(shopOrderMapper.selectByRequestId(USER_ID, REQUEST_ID)).thenReturn(null);
        stubOrderCreation();

        seckillService.processSeckill(ACTIVITY_ID, USER_ID, REQUEST_ID);

        // 建单落成"待支付现金单"：不扣款、不推 PAID，由 beginPaymentAfterCreated 挂支付倒计时
        ArgumentCaptor<ShopOrder> captor = ArgumentCaptor.forClass(ShopOrder.class);
        verify(shopOrderMapper).insert(captor.capture());
        ShopOrder saved = captor.getValue();
        assertEquals("CASH", saved.getPayType());
        assertEquals("PENDING", saved.getStatus());
        assertEquals(ACTIVITY_ID, saved.getSeckillActivityId());
        // 结果必须是 SUCCESS:<orderNo>:<orderId>（orderId 由 stub 回填为 99），前端据此跳收银台
        verify(resultBucket).set(eq("SUCCESS:" + saved.getOrderNo() + ":99"), anyLong(), any());
        verify(shopOrderService).beginPaymentAfterCreated(saved);
    }

    @Test
    void processSeckill_shouldRestockRedisAndFail_whenQuotaTakenInDb() {
        when(shopOrderMapper.selectByRequestId(USER_ID, REQUEST_ID)).thenReturn(null);
        when(seckillActivityMapper.selectById(ACTIVITY_ID)).thenReturn(ongoingActivity());
        // DB 名额已被抢完 → 业务失败（钱没动过），事务回滚
        when(seckillActivityMapper.increaseSold(ACTIVITY_ID, 1)).thenReturn(0);

        seckillService.processSeckill(ACTIVITY_ID, USER_ID, REQUEST_ID);

        // 业务失败：DB 名额随事务回滚撤销，只需回补 Redis；不建单、不启动支付
        verify(resultBucket).set(startsWith("FAILED:已抢完"), anyLong(), any());
        verify(seckillSlotService).restockRedis(ACTIVITY_ID, USER_ID);
        verify(shopOrderMapper, never()).insert(any(ShopOrder.class));
        verify(shopOrderService, never()).beginPaymentAfterCreated(any());
    }

    @Test
    void processSeckill_shouldNotRestock_whenUnexpectedError() {
        // 系统异常：名额不回补（少卖方向安全），结果 FAILED:系统繁忙
        when(shopOrderMapper.selectByRequestId(USER_ID, REQUEST_ID)).thenReturn(null);
        when(seckillActivityMapper.selectById(ACTIVITY_ID)).thenThrow(new RuntimeException("DB down"));

        seckillService.processSeckill(ACTIVITY_ID, USER_ID, REQUEST_ID);

        verify(resultBucket).set(startsWith("FAILED:系统繁忙"), anyLong(), any());
        verify(seckillSlotService, never()).restockRedis(anyLong(), anyLong());
    }

    @Test
    void processSeckill_shouldBeIdempotent_whenRequestAlreadyExists() {
        ShopOrder existing = new ShopOrder();
        existing.setOrderNo("EXIST-1");
        when(shopOrderMapper.selectByRequestId(USER_ID, REQUEST_ID)).thenReturn(existing);

        seckillService.processSeckill(ACTIVITY_ID, USER_ID, REQUEST_ID);

        verify(resultBucket).set(eq("SUCCESS:EXIST-1"), anyLong(), any());
        verify(shopOrderMapper, never()).insert(any(ShopOrder.class));
    }

    @Test
    void processSeckill_shouldMarkSuccess_whenRequestDuplicateKey() {
        // 极端并发下 requestId 撞键：按原单成功处理（幂等），不再走失败分支
        when(shopOrderMapper.selectByRequestId(USER_ID, REQUEST_ID)).thenReturn(null);
        when(seckillActivityMapper.selectById(ACTIVITY_ID)).thenReturn(ongoingActivity());
        when(seckillActivityMapper.increaseSold(ACTIVITY_ID, 1)).thenReturn(1);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenThrow(new DuplicateKeyException("uk_user_order_request"));

        seckillService.processSeckill(ACTIVITY_ID, USER_ID, REQUEST_ID);

        verify(resultBucket).set(startsWith("SUCCESS:"), anyLong(), any());
        verify(resultBucket).set(anyString(), anyLong(), any());
    }

    // ===== 活动信息缓存：抢购入口窗口校验读缓存，不逐请求查 DB =====

    @Test
    void seckill_shouldValidateFromCache_withoutDbHit_whenCacheHit() {
        doReturn(JSONUtil.toJsonStr(ongoingActivity())).when(resultBucket).get();
        doReturn(1L).when(seckillService).evalLua(anyString(), eq(ACTIVITY_ID), eq(USER_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.seckill(USER_ID, ACTIVITY_ID, "tk"));
        assertTrue(ex.getMessage().contains("已抢购过"));
        // 缓存命中：入口全程未查 DB（Lua 返回 1L 仅作为"走到了预扣"的探针）
        verify(seckillActivityMapper, never()).selectById(anyLong());
    }

    @Test
    void seckill_shouldReject_whenCachedActivityEnded() {
        // 缓存里存的是起止时间，窗口状态按当前时间实时计算——到点即生效，不等预热周期
        SeckillActivity ended = ongoingActivity();
        ended.setEndTime(LocalDateTime.now().minusMinutes(1));
        doReturn(JSONUtil.toJsonStr(ended)).when(resultBucket).get();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.seckill(USER_ID, ACTIVITY_ID, "tk"));
        assertTrue(ex.getMessage().contains("已结束"));
        verify(seckillActivityMapper, never()).selectById(anyLong());
        verify(seckillService, never()).evalLua(anyString(), anyLong(), anyLong());
    }

    @Test
    void seckill_shouldFallBackToDbAndBackfill_whenCacheMiss() {
        when(seckillActivityMapper.selectById(ACTIVITY_ID)).thenReturn(ongoingActivity());
        doReturn(1L).when(seckillService).evalLua(anyString(), eq(ACTIVITY_ID), eq(USER_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.seckill(USER_ID, ACTIVITY_ID, "tk"));
        assertTrue(ex.getMessage().contains("已抢购过"));
        // 未命中回源 DB，并把活动 JSON 回填进缓存
        verify(seckillActivityMapper).selectById(ACTIVITY_ID);
        verify(resultBucket).set(contains("seckillPrice"), anyLong(), any());
    }

    @Test
    void seckill_shouldFallBackToDb_whenCacheReadThrows() {
        // 缓存故障按"未命中"降级（fail-open 方向）：入口多查一次库，不放大成抢购不可用
        when(resultBucket.get()).thenThrow(new RuntimeException("redis down"));
        when(seckillActivityMapper.selectById(ACTIVITY_ID)).thenReturn(ongoingActivity());
        doReturn(1L).when(seckillService).evalLua(anyString(), eq(ACTIVITY_ID), eq(USER_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> seckillService.seckill(USER_ID, ACTIVITY_ID, "tk"));
        assertTrue(ex.getMessage().contains("已抢购过"));
        verify(seckillActivityMapper).selectById(ACTIVITY_ID);
    }

    @Test
    void activityCache_shouldRoundTrip_timeFields() {
        // hutool JSON 对 LocalDateTime/BigDecimal 的往返必须无损，缓存校验才可信
        SeckillActivity activity = ongoingActivity();
        activity.setStartTime(LocalDateTime.now().minusMinutes(30).truncatedTo(ChronoUnit.SECONDS));
        activity.setEndTime(LocalDateTime.now().plusMinutes(30).truncatedTo(ChronoUnit.SECONDS));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        seckillService.cacheActivity(activity);
        verify(resultBucket).set(jsonCaptor.capture(), anyLong(), any());

        when(resultBucket.get()).thenReturn(jsonCaptor.getValue());
        SeckillActivity parsed = seckillService.readCachedActivity(ACTIVITY_ID);
        assertNotNull(parsed);
        assertEquals(activity.getStartTime(), parsed.getStartTime());
        assertEquals(activity.getEndTime(), parsed.getEndTime());
        assertEquals(activity.getStatus(), parsed.getStatus());
        // hutool 往返会丢 BigDecimal 的 scale（9.90→9.9）：数值必须相等；
        // 缓存副本只做窗口校验，价格权威仍以落库时读 DB 为准
        assertEquals(0, activity.getSeckillPrice().compareTo(parsed.getSeckillPrice()));
    }

    @Test
    void evictActivityCache_shouldDeleteKey() {
        seckillService.evictActivityCache(ACTIVITY_ID);
        verify(resultBucket).delete();
    }
}
