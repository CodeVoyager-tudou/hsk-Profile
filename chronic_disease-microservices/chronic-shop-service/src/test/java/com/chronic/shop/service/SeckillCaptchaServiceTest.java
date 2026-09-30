package com.chronic.shop.service;

import com.chronic.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 秒杀滑块验证码单测（Redis 用 mock，图像绘制真实执行）。
 * 重点验证防伪语义：位置容差、验证即销毁（单次尝试）、用户绑定、出题限频、票据一次性。
 *
 * @author chronic
 */
@ExtendWith(MockitoExtension.class)
class SeckillCaptchaServiceTest {

    private static final long USER_ID = 1L;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBucket<String> bucket;

    private SeckillCaptchaService captchaService;

    @BeforeEach
    void setUp() {
        captchaService = new SeckillCaptchaService(redissonClient);
    }

    @Test
    @DisplayName("出题限频：每分钟超过 20 次直接 429")
    void generate_shouldLimitFrequency() {
        org.redisson.api.RAtomicLong counter = mock(org.redisson.api.RAtomicLong.class);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(counter);
        when(counter.incrementAndGet()).thenReturn(21L);

        assertThrows(BusinessException.class, () -> captchaService.generate(USER_ID));
        // 第 21 次直接拒绝，不应再续期窗口（expire 只发生在首次计数）
        verify(counter, never()).expire(anyLong(), any());
    }

    @Test
    @DisplayName("出题：返回验证码ID/双图/碎块纵坐标，Redis 记账（绑定 uid 与缺口 x）")
    void generate_shouldStoreBoundAnswerAndReturnImages() {
        doReturn(bucket).when(redissonClient).getBucket(anyString(), any(Codec.class));
        org.redisson.api.RAtomicLong counter = mock(org.redisson.api.RAtomicLong.class);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(counter);
        when(counter.incrementAndGet()).thenReturn(1L);

        SeckillCaptchaService.Captcha captcha = captchaService.generate(USER_ID);

        assertNotNull(captcha.captchaId);
        assertTrue(captcha.bgDataUrl.startsWith("data:image/bmp;base64,"), "底图应为 BMP data URL");
        assertTrue(captcha.pieceDataUrl.startsWith("data:image/png;base64,"), "碎块应为 PNG data URL");
        assertTrue(captcha.pieceY >= 20 && captcha.pieceY <= 96, "碎块纵坐标应在画面内: " + captcha.pieceY);
        verify(bucket).set(startsWith(USER_ID + ":"), anyLong(), any());
    }

    @Test
    @DisplayName("验证通过：容差内 + 轨迹合法，签发票据，且验证码当场销毁")
    void verify_shouldIssueTicketWithinTolerance() {
        doReturn(bucket).when(redissonClient).getBucket(anyString(), any(Codec.class));
        when(bucket.getAndDelete()).thenReturn(USER_ID + ":150");

        String ticket = captchaService.verify(USER_ID, "cid", 153, "0,40,90,120,153", 600); // 偏 3px，容差 6px 内

        assertNotNull(ticket);
        verify(bucket).set(eq(ticket), anyLong(), any());
    }

    @Test
    @DisplayName("轨迹行为校验：点数不足 / 时长过短 / 终点不一致，一律拒绝")
    void verify_shouldReject_onAbnormalTrajectory() {
        doReturn(bucket).when(redissonClient).getBucket(anyString(), any(Codec.class));
        when(bucket.getAndDelete()).thenReturn(USER_ID + ":150");

        // 采样点数不足（脚本秒回，只有 2 个点）
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> captchaService.verify(USER_ID, "cid", 150, "0,150", 600));
        assertTrue(ex1.getMessage().contains("行为异常"));
        // 时长过短（10ms 内"完成"拖拽，不符合真人行为）
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> captchaService.verify(USER_ID, "cid", 150, "0,40,90,120,150", 10));
        assertTrue(ex2.getMessage().contains("行为异常"));
        // 轨迹终点与提交位置互相矛盾
        BusinessException ex3 = assertThrows(BusinessException.class,
                () -> captchaService.verify(USER_ID, "cid", 150, "0,40,90,120,199", 600));
        assertTrue(ex3.getMessage().contains("行为异常"));
    }

    @Test
    @DisplayName("验证失败：超出容差/过期/换用户，均不签发票据")
    void verify_shouldReject_onToleranceMissExpiryAndOwnerMismatch() {
        doReturn(bucket).when(redissonClient).getBucket(anyString(), any(Codec.class));

        // 超容差（偏 10px）
        when(bucket.getAndDelete()).thenReturn(USER_ID + ":150");
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> captchaService.verify(USER_ID, "cid", 160, "0,40,90,120,160", 600));
        assertTrue(ex1.getMessage().contains("滑块位置不对"));

        // 已过期/已被使用
        when(bucket.getAndDelete()).thenReturn(null);
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> captchaService.verify(USER_ID, "cid", 150, "0,40,90,120,150", 600));
        assertTrue(ex2.getMessage().contains("已过期"));

        // 换用户（验证码绑定签发者）
        when(bucket.getAndDelete()).thenReturn((USER_ID + 9) + ":150");
        BusinessException ex3 = assertThrows(BusinessException.class,
                () -> captchaService.verify(USER_ID, "cid", 150, "0,40,90,120,150", 600));
        assertTrue(ex3.getMessage().contains("不匹配"));

        verify(bucket, never()).set(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("票据消费：CAS 原子取走即作废，重复/缺失/错票一律拒绝")
    void consumeTicket_shouldBeOneTime() {
        doReturn(bucket).when(redissonClient).getBucket(anyString(), any(Codec.class));

        // 正常消费：get 命中 + CAS 置空成功
        when(bucket.get()).thenReturn("T1");
        when(bucket.compareAndSet("T1", null)).thenReturn(true);
        assertTrue(captchaService.consumeTicket(USER_ID, "T1"));

        // 票据值不符
        when(bucket.get()).thenReturn("T1");
        assertFalse(captchaService.consumeTicket(USER_ID, "T2"));

        // 票据已不存在
        when(bucket.get()).thenReturn(null);
        assertFalse(captchaService.consumeTicket(USER_ID, "T1"));

        // null/空串直接拒绝，不触达 Redis
        assertFalse(captchaService.consumeTicket(USER_ID, null));
        assertFalse(captchaService.consumeTicket(USER_ID, " "));
    }
}
