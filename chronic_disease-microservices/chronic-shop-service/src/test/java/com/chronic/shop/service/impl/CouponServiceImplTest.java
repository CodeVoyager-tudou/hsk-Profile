package com.chronic.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chronic.common.exception.BusinessException;
import com.chronic.shop.entity.Coupon;
import com.chronic.shop.entity.UserCoupon;
import com.chronic.shop.mapper.CouponMapper;
import com.chronic.shop.mapper.UserCouponMapper;
import com.chronic.shop.vo.UserCouponVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponServiceImplTest {

    /** 锁粒度=单个用户 x 单个活动 */
    private static final String LOCK_KEY = "chronic:lock:coupon:receive:1:1";

    @Mock
    private CouponMapper couponMapper;

    @Mock
    private UserCouponMapper userCouponMapper;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    private CouponServiceImpl couponService;

    @BeforeEach
    void setUp() {
        // 默认不注入 Redisson：验证无锁/降级路径下仍由数据库唯一键兜底
        couponService = newService(Optional.empty());
    }

    private CouponServiceImpl newService(Optional<RedissonClient> client) {
        CouponServiceImpl service = new CouponServiceImpl(couponMapper, userCouponMapper, client);
        ReflectionTestUtils.setField(service, "baseMapper", couponMapper);
        return service;
    }

    private Coupon activeCoupon(int limitPerUser) {
        Coupon coupon = new Coupon();
        coupon.setId(1L);
        coupon.setName("测试券");
        coupon.setStatus(1);
        coupon.setStartTime(LocalDateTime.now().minusDays(1));
        coupon.setEndTime(LocalDateTime.now().plusDays(30));
        coupon.setTotalCount(100);
        coupon.setIssuedCount(50);
        coupon.setLimitPerUser(limitPerUser);
        return coupon;
    }

    @Test
    void listActive_shouldReturnActiveCoupons() {
        Coupon coupon = new Coupon();
        coupon.setId(1L);
        coupon.setName("高血压专享券");
        coupon.setDiscountAmount(BigDecimal.valueOf(12));
        coupon.setThresholdAmount(BigDecimal.valueOf(50));
        coupon.setEndTime(LocalDateTime.now().plusDays(30));

        when(couponMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(coupon));

        List<Coupon> result = couponService.listActive();
        assertEquals(1, result.size());
        assertEquals("高血压专享券", result.get(0).getName());
    }

    @Test
    void receive_shouldCreateUserCoupon() {
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1));
        when(userCouponMapper.selectMaxReceiveNo(1L, 1L)).thenReturn(0);
        when(couponMapper.increaseIssuedCount(1L)).thenReturn(1);
        when(userCouponMapper.insert(any(UserCoupon.class))).thenReturn(1);

        UserCoupon result = couponService.receive(1L, 1L);

        assertNotNull(result);
        assertEquals(1L, result.getCouponId());
        assertEquals(1L, result.getUserId());
        assertEquals("UNUSED", result.getStatus());
        // 业务唯一ID：第一次领取序号为 1
        assertEquals(1, result.getReceiveNo());
        verify(userCouponMapper, times(1)).insert(any(UserCoupon.class));
    }

    @Test
    void receive_shouldThrow_whenCouponNotFound() {
        when(couponMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.receive(1L, 99L));
        assertEquals("优惠券活动不存在", ex.getMessage());
    }

    @Test
    void receive_shouldThrow_whenAlreadyReceived() {
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1));
        when(userCouponMapper.selectMaxReceiveNo(1L, 1L)).thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.receive(1L, 1L));
        assertEquals("已达每人限领上限", ex.getMessage());
        // 已在限领校验被拦下，不应占用名额
        verify(couponMapper, never()).increaseIssuedCount(anyLong());
    }

    @Test
    void receive_shouldThrow_whenStockEmpty() {
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(5));
        when(userCouponMapper.selectMaxReceiveNo(1L, 1L)).thenReturn(0);
        when(couponMapper.increaseIssuedCount(1L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.receive(1L, 1L));
        assertEquals("优惠券已被领完", ex.getMessage());
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void receive_shouldThrow_whenDuplicateKeyBlockedByUniqueIndex() {
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1));
        when(userCouponMapper.selectMaxReceiveNo(1L, 1L)).thenReturn(0);
        when(couponMapper.increaseIssuedCount(1L)).thenReturn(1);
        when(userCouponMapper.insert(any(UserCoupon.class)))
                .thenThrow(new DuplicateKeyException("uk_user_coupon_user_coupon_no"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.receive(1L, 1L));
        assertEquals("已达每人限领上限", ex.getMessage());
    }

    @Test
    void receive_shouldThrow_whenLockNotAcquired() throws Exception {
        CouponServiceImpl service = newService(Optional.of(redissonClient));
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1));
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.receive(1L, 1L));
        assertEquals("领取请求处理中，请稍后重试", ex.getMessage());
        verify(couponMapper, never()).increaseIssuedCount(anyLong());
        verify(rLock, never()).unlock();
    }

    @Test
    void receive_shouldLockByUserAndCoupon_thenUnlock() throws Exception {
        CouponServiceImpl service = newService(Optional.of(redissonClient));
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1));
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(userCouponMapper.selectMaxReceiveNo(1L, 1L)).thenReturn(0);
        when(couponMapper.increaseIssuedCount(1L)).thenReturn(1);
        when(userCouponMapper.insert(any(UserCoupon.class))).thenReturn(1);

        service.receive(1L, 1L);

        verify(redissonClient).getLock(LOCK_KEY);
        verify(rLock).unlock();
    }

    @Test
    void receive_shouldDegrade_whenRedisUnavailable() {
        CouponServiceImpl service = newService(Optional.of(redissonClient));
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1));
        when(redissonClient.getLock(LOCK_KEY)).thenThrow(new IllegalStateException("Redis connection refused"));
        when(userCouponMapper.selectMaxReceiveNo(1L, 1L)).thenReturn(0);
        when(couponMapper.increaseIssuedCount(1L)).thenReturn(1);
        when(userCouponMapper.insert(any(UserCoupon.class))).thenReturn(1);

        UserCoupon result = service.receive(1L, 1L);

        // Redis 挂了也不阻断领取，数据由唯一键守住
        assertEquals(1, result.getReceiveNo());
    }

    /**
     * 无锁(降级)路径下的并发领取：所有线程都读到"未领取"，
     * 但唯一键只允许一条 (user, coupon, receiveNo)，因此成功数必须等于限领数
     */
    @Test
    void receive_concurrentWithoutLock_shouldNotExceedLimitPerUser() throws Exception {
        int threads = 50;
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(1));
        when(userCouponMapper.selectMaxReceiveNo(1L, 1L)).thenReturn(0);
        when(couponMapper.increaseIssuedCount(1L)).thenReturn(1);
        Set<String> uniqueKeys = ConcurrentHashMap.newKeySet();
        when(userCouponMapper.insert(any(UserCoupon.class))).thenAnswer(invocation -> {
            UserCoupon uc = invocation.getArgument(0);
            if (!uniqueKeys.add(uc.getUserId() + ":" + uc.getCouponId() + ":" + uc.getReceiveNo())) {
                throw new DuplicateKeyException("uk_user_coupon_user_coupon_no");
            }
            return 1;
        });

        assertEquals(1, countSuccess(couponService, threads), "并发下领取数不能超过每人限领");
    }

    /**
     * 有锁路径下的并发领取：Redisson 互斥 + 唯一键兜底，成功数同样不超过限领数
     */
    @Test
    void receive_concurrentWithLock_shouldNotExceedLimitPerUser() throws Exception {
        int threads = 30;
        int limitPerUser = 2;
        when(couponMapper.selectById(1L)).thenReturn(activeCoupon(limitPerUser));
        when(couponMapper.increaseIssuedCount(1L)).thenReturn(1);

        // 用真实 ReentrantLock 模拟 Redisson 的互斥语义(每线程各持锁串行进入临界区)
        ReentrantLock mutex = new ReentrantLock();
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(rLock);
        // Redisson 的 tryLock(waitTime, leaseTime, unit) 会等待，这里用带超时的 tryLock 等价模拟
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> mutex.tryLock(5, TimeUnit.SECONDS));
        when(rLock.isHeldByCurrentThread()).thenAnswer(invocation -> mutex.isHeldByCurrentThread());
        doAnswer(invocation -> {
            mutex.unlock();
            return null;
        }).when(rLock).unlock();

        AtomicInteger maxReceiveNo = new AtomicInteger(0);
        Set<String> uniqueKeys = ConcurrentHashMap.newKeySet();
        when(userCouponMapper.selectMaxReceiveNo(1L, 1L)).thenAnswer(invocation -> maxReceiveNo.get());
        when(userCouponMapper.insert(any(UserCoupon.class))).thenAnswer(invocation -> {
            UserCoupon uc = invocation.getArgument(0);
            if (!uniqueKeys.add(uc.getUserId() + ":" + uc.getCouponId() + ":" + uc.getReceiveNo())) {
                throw new DuplicateKeyException("uk_user_coupon_user_coupon_no");
            }
            maxReceiveNo.accumulateAndGet(uc.getReceiveNo(), Math::max);
            return 1;
        });

        CouponServiceImpl service = newService(Optional.of(redissonClient));

        assertEquals(limitPerUser, countSuccess(service, threads), "并发下领取数不能超过每人限领");
    }

    /**
     * 并发发起 N 次领取，返回成功次数(业务异常=被限领/售罄规则拦下)
     */
    private int countSuccess(CouponServiceImpl service, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.receive(1L, 1L);
                        return true;
                    } catch (BusinessException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int success = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    success++;
                }
            }
            return success;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void listMyCoupons_shouldReturnUserCoupons() {
        UserCoupon uc = new UserCoupon();
        uc.setUserId(1L);
        uc.setCouponId(1L);
        uc.setStatus("UNUSED");

        Coupon coupon = new Coupon();
        coupon.setId(1L);
        coupon.setName("测试券");
        coupon.setDiscountAmount(BigDecimal.valueOf(10));

        when(userCouponMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(uc));
        when(couponMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(coupon));

        List<UserCouponVO> result = couponService.listMyCoupons(1L, null);
        assertEquals(1, result.size());
    }

    @Test
    void listMyCoupons_shouldReturnEmpty_whenNoCoupons() {
        when(userCouponMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        List<UserCouponVO> result = couponService.listMyCoupons(1L, "UNUSED");
        assertEquals(0, result.size());
    }

    @Test
    void markUsed_shouldUpdateStatus() {
        when(userCouponMapper.markUsed(1L, 1L, 100L)).thenReturn(1);

        assertDoesNotThrow(() -> couponService.markUsed(1L, 1L, 100L));
        verify(userCouponMapper, times(1)).markUsed(1L, 1L, 100L);
    }

    @Test
    void markUsed_shouldThrow_whenCouponNotAvailable() {
        when(userCouponMapper.markUsed(1L, 1L, 100L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.markUsed(1L, 1L, 100L));
        assertEquals("优惠券不可用（不存在、已使用或已过期）", ex.getMessage());
    }

    @Test
    void markUnused_shouldUpdateStatus() {
        // J-06 修复后签名带 orderId：退回时必须校验「这张券确实由本单使用」且「仍未过期」
        when(userCouponMapper.markUnused(1L, 100L)).thenReturn(1);

        assertDoesNotThrow(() -> couponService.markUnused(1L, 100L));
        verify(userCouponMapper, times(1)).markUnused(1L, 100L);
        // 已成功退回为 UNUSED，就不应再走"置为 EXPIRED"的分支
        verify(userCouponMapper, never()).markExpiredIfUsedByOrder(anyLong(), anyLong());
    }

    @Test
    void markUnused_shouldMarkExpired_whenCouponAlreadyExpired() {
        // J-06 回归：券已过期时不能退回成 UNUSED（那会变成一张可用的过期券，形成折扣套利），
        // 而应置为 EXPIRED。
        when(userCouponMapper.markUnused(1L, 100L)).thenReturn(0);
        when(userCouponMapper.markExpiredIfUsedByOrder(1L, 100L)).thenReturn(1);

        couponService.markUnused(1L, 100L);

        verify(userCouponMapper, times(1)).markUnused(1L, 100L);
        verify(userCouponMapper, times(1)).markExpiredIfUsedByOrder(1L, 100L);
    }
}
