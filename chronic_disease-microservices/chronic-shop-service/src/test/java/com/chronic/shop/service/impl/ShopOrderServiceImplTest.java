package com.chronic.shop.service.impl;

import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chronic.shop.entity.CompensationTask;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.feign.PointsFeignClient;
import com.chronic.shop.mapper.CompensationTaskMapper;
import com.chronic.shop.mapper.MedicineMapper;
import com.chronic.shop.mapper.ShopOrderMapper;
import com.chronic.shop.mapper.UserCouponMapper;
import com.chronic.shop.mq.OrderEventPublisher;
import com.chronic.shop.mq.PayTimeoutPublisher;
import com.chronic.shop.pay.OrderPaymentService;
import com.chronic.shop.pay.PaymentGateway;
import com.chronic.shop.service.SeckillSlotService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShopOrderServiceImplTest {

    @Mock
    private MedicineMapper medicineMapper;

    @Mock
    private ShopOrderMapper shopOrderMapper;

    @Mock
    private UserCouponMapper userCouponMapper;

    @Mock
    private MedicineServiceImpl medicineService;

    @Mock
    private CouponServiceImpl couponService;

    @Mock
    private PointsFeignClient pointsFeignClient;

    @Mock
    private CompensationTaskMapper compensationTaskMapper;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private OrderPaymentService orderPaymentService;

    @Mock
    private PayTimeoutPublisher payTimeoutPublisher;

    @Mock
    private SeckillSlotService seckillSlotService;

    private ShopOrderServiceImpl shopOrderService;

    /**
     * 建立实体的 TableInfo 缓存：LambdaQueryWrapper.getSqlSegment() 靠它把方法引用
     * 解析成列名（平时由 MyBatis-Plus 在 mapper 注册时建立）。纯单测没有 Spring 容器，
     * 不初始化就会抛 "can not find lambda cache for this entity"，也就断言不了 SQL。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ShopOrder.class);
    }

    private Medicine createMedicine() {
        Medicine m = new Medicine();
        m.setId(1L);
        m.setName("苯磺酸氨氯地平片");
        m.setPrice(BigDecimal.valueOf(28.5));
        m.setStock(100);
        m.setStatus(1);
        m.setPointsPrice(850);
        m.setPointsReward(28);
        return m;
    }

    @BeforeEach
    void setUp() {
        OrderEventPublisher orderEventPublisher = new OrderEventPublisher(Optional.empty());
        shopOrderService = new ShopOrderServiceImpl(medicineService, medicineMapper, userCouponMapper, couponService,
                pointsFeignClient, compensationTaskMapper, paymentGateway, orderPaymentService, orderEventPublisher,
                payTimeoutPublisher, seckillSlotService);
        ReflectionTestUtils.setField(shopOrderService, "baseMapper", shopOrderMapper);
        // 本地演示渠道默认即时确认（生产由真实渠道回调推进）
        lenient().when(paymentGateway.confirmImmediately()).thenReturn(true);
        lenient().when(orderPaymentService.confirmPaid(any())).thenAnswer(invocation -> {
            ShopOrder paid = new ShopOrder();
            paid.setId(1L);
            paid.setStatus("PAID");
            paid.setPayTime(LocalDateTime.now());
            paid.setPointsStatus(1);
            return paid;
        });
    }

    @Test
    void createOrder_shouldCreateOrderSuccessfully() {
        Medicine medicine = createMedicine();
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 2)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);

        ShopOrder order = shopOrderService.createOrder(1L, 1L, 2, null);

        assertNotNull(order);
        assertEquals("CASH", order.getPayType());
        assertEquals("PAID", order.getStatus());
        assertEquals(2, order.getQuantity());
        assertEquals(BigDecimal.valueOf(57.0), order.getTotalAmount());
        verify(medicineService, times(1)).reduceStock(1L, 2);
        // 支付确认（支付成功后才发积分，发放逻辑见 OrderPaymentServiceTest）
        verify(orderPaymentService, times(1)).confirmPaid(any());
    }

    @Test
    void createOrder_shouldNotFail_whenAddPointsReturnsError() {
        // Sentinel 降级时 fallback 返回 Result.error 而非抛异常：
        // 主流程应继续（订单创建成功），积分丢失仅记录待补偿日志
        Medicine medicine = createMedicine();
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 2)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);

        ShopOrder order = shopOrderService.createOrder(1L, 1L, 2, null);

        assertNotNull(order);
        assertEquals("PAID", order.getStatus());
    }

    @Test
    void createOrder_shouldThrow_whenQuantityExceedsMax() {
        assertThrows(BusinessException.class,
                () -> shopOrderService.createOrder(1L, 1L, 100, null));
    }

    @Test
    void createOrder_shouldThrow_whenQuantityZero() {
        assertThrows(BusinessException.class,
                () -> shopOrderService.createOrder(1L, 1L, 0, null));
    }

    @Test
    void createOrder_shouldThrow_whenQuantityNull() {
        assertThrows(BusinessException.class,
                () -> shopOrderService.createOrder(1L, 1L, null, null));
    }

    @Test
    void createOrder_shouldThrow_whenMedicineNotOnSale() {
        when(medicineMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> shopOrderService.createOrder(1L, 1L, 1, null));
        // 未上架不允许下单：库存不应被扣减
        verify(medicineService, never()).reduceStock(anyLong(), anyInt());
    }

    @Test
    void createOrder_shouldThrow_whenMedicineOffShelf() {
        Medicine medicine = createMedicine();
        medicine.setStatus(0);
        when(medicineMapper.selectById(1L)).thenReturn(medicine);

        assertThrows(BusinessException.class,
                () -> shopOrderService.createOrder(1L, 1L, 1, null));
    }

    @Test
    void exchangeOrder_shouldCreatePointsOrder() {
        Medicine medicine = createMedicine();
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 1)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);
        Result<Boolean> deductResult = Result.success(true);
        when(pointsFeignClient.deductPoints(anyLong(), anyInt(), anyString(), nullable(Long.class), anyString()))
                .thenReturn(deductResult);

        ShopOrder order = shopOrderService.exchangeOrder(1L, 1L, 1);

        assertNotNull(order);
        assertEquals("POINTS", order.getPayType());
        assertEquals(850, order.getPointsUsed());
        assertEquals(0, order.getPointsEarned());
        verify(pointsFeignClient, times(1)).deductPoints(eq(1L), eq(850), anyString(), nullable(Long.class), anyString());
    }

    @Test
    void exchangeOrder_shouldThrow_whenPointsPriceNotSet() {
        Medicine medicine = createMedicine();
        medicine.setPointsPrice(0);
        when(medicineMapper.selectById(1L)).thenReturn(medicine);

        assertThrows(BusinessException.class,
                () -> shopOrderService.exchangeOrder(1L, 1L, 1));
    }

    @Test
    void cancelOrder_shouldCancelOrderAndRevokeEarnedPoints() {
        ShopOrder order = new ShopOrder();
        order.setId(1L);
        order.setUserId(1L);
        order.setStatus("PAID");
        order.setPayType("CASH");
        order.setMedicineId(1L);
        order.setQuantity(2);
        order.setTotalAmount(BigDecimal.valueOf(57.0));
        order.setPointsEarned(56);

        when(shopOrderMapper.selectById(1L)).thenReturn(order);
        when(shopOrderMapper.cancelIfNotCancelled(1L)).thenReturn(1);
        when(medicineService.restoreStock(1L, 2)).thenReturn(true);
        when(pointsFeignClient.deductPoints(anyLong(), anyInt(), anyString(), nullable(Long.class), anyString()))
                .thenReturn(Result.success(true));

        boolean result = shopOrderService.cancelOrder(1L, 1L);
        assertTrue(result);
        verify(medicineService, times(1)).restoreStock(1L, 2);
        // 现金单取消应回收下单赠送的积分（幂等键 type+sourceId）
        verify(pointsFeignClient, times(1)).deductPoints(eq(1L), eq(56),
                eq("ORDER_CANCEL_REVOKE"), eq(1L), anyString());
        // 退款记账：按应付金额（总额-优惠）回填
        //
        // J-05 修复后**不再**调用 updateById(order)。
        // 原因：order 是取消前的快照，status 仍是 'PAID'，而 cancelIfNotCancelled
        // 已把库里改成 'CANCELLED'；整体写回会把 status 覆盖回 PAID，
        // 使这单能被反复取消（每次退库存+退券）。
        // 现在改为只更新退款两列的定向 UPDATE。
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(shopOrderMapper, times(1))
                .updateRefundInfo(eq(1L), amountCaptor.capture(), any());
        assertEquals(0, BigDecimal.valueOf(57.0).compareTo(amountCaptor.getValue()),
                "退款金额应为 57.0");
        // 关键回归断言：绝不能再用整体写回（否则 CANCELLED 会被覆盖回 PAID）
        verify(shopOrderMapper, never()).updateById(any(ShopOrder.class));
    }

    @Test
    void cancelOrder_mustNotOverwriteCancelledStatusBackToPaid() {
        // J-05 回归：取消后状态必须保持 CANCELLED，不得被快照里的旧状态覆盖。
        // 用一个"会在 updateById 时把 status 改回去"的桩来模拟原缺陷的危害。
        ShopOrder order = new ShopOrder();
        order.setId(1L);
        order.setUserId(1L);
        order.setStatus("PAID");          // 取消前的快照状态
        order.setPayType("CASH");
        order.setMedicineId(1L);
        order.setQuantity(1);
        order.setTotalAmount(BigDecimal.valueOf(10.0));

        when(shopOrderMapper.selectById(1L)).thenReturn(order);
        when(shopOrderMapper.cancelIfNotCancelled(1L)).thenReturn(1);
        when(medicineService.restoreStock(1L, 1)).thenReturn(true);

        assertTrue(shopOrderService.cancelOrder(1L, 1L));

        // 只要不调用 updateById，就不可能把 CANCELLED 覆盖回 PAID
        verify(shopOrderMapper, never()).updateById(any(ShopOrder.class));
        verify(shopOrderMapper, times(1)).updateRefundInfo(eq(1L), any(), any());
    }

    @Test
    void cancelOrder_shouldThrow_whenNotOwner() {
        ShopOrder order = new ShopOrder();
        order.setId(1L);
        order.setUserId(2L);
        order.setStatus("PAID");

        when(shopOrderMapper.selectById(1L)).thenReturn(order);

        assertThrows(BusinessException.class,
                () -> shopOrderService.cancelOrder(1L, 1L));
        // 越权取消不允许发生：不应触碰取消标记和库存
        verify(shopOrderMapper, never()).cancelIfNotCancelled(anyLong());
        verify(medicineService, never()).restoreStock(anyLong(), anyInt());
    }

    @Test
    void cancelOrder_shouldThrow_whenOrderNotFound() {
        when(shopOrderMapper.selectById(99L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> shopOrderService.cancelOrder(99L, 1L));
    }

    @Test
    void cancelOrder_shouldThrow_whenAlreadyCancelled() {
        ShopOrder order = new ShopOrder();
        order.setId(1L);
        order.setUserId(1L);
        order.setStatus("CANCELLED");

        when(shopOrderMapper.selectById(1L)).thenReturn(order);
        // 条件更新未命中：另一个并发请求已抢占取消成功
        when(shopOrderMapper.cancelIfNotCancelled(1L)).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> shopOrderService.cancelOrder(1L, 1L));
    }

    @Test
    void getOrderForUser_shouldThrow_whenNotOwner() {
        ShopOrder order = new ShopOrder();
        order.setId(1L);
        order.setUserId(2L);

        when(shopOrderMapper.selectById(1L)).thenReturn(order);

        assertThrows(BusinessException.class,
                () -> shopOrderService.getOrderForUser(1L, 1L));
    }

    @Test
    void createOrder_shouldReturnExistingOrder_whenRequestIdRepeated() {
        // 幂等键：前端重试/双击时不该重复扣库存、重复建单
        ShopOrder existing = new ShopOrder();
        existing.setId(99L);
        existing.setOrderNo("EXISTING-ORDER");
        existing.setStatus("PAID");
        when(shopOrderMapper.selectByRequestId(1L, "req-1")).thenReturn(existing);

        ShopOrder order = shopOrderService.createOrder(1L, 1L, 2, null, "req-1");

        assertEquals("EXISTING-ORDER", order.getOrderNo());
        verify(medicineService, never()).reduceStock(anyLong(), anyInt());
        verify(shopOrderMapper, never()).insert(any(ShopOrder.class));
    }

    // ===== J-03 修复的回归防护 =====
    // 缺陷背景：原实现把「远程扣积分」放在本地事务内部，且 Feign readTimeout=3s。
    // 于是存在窗口：扣分已在 points-service 生效，但本地事务随后回滚
    // -> 订单没了、库存归还了、积分却被扣走且**补偿台账为空**（异常回滚会连台账一起撤销），
    //    积分永久丢失。
    // 修复：改为**先落单、后扣分**；扣分失败回滚事务并写补偿台账以便核对退还。

    @Test
    void exchangeOrder_shouldInsertOrderBeforeDeductingPoints() {
        // 核心修复点：落单必须先于远程扣分，避免「扣了分却没有订单」
        Medicine medicine = createMedicine();
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 1)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);
        when(pointsFeignClient.deductPoints(anyLong(), anyInt(), anyString(), nullable(Long.class), anyString()))
                .thenReturn(Result.success(true));

        shopOrderService.exchangeOrder(1L, 1L, 1);

        InOrder inOrder = inOrder(shopOrderMapper, pointsFeignClient);
        inOrder.verify(shopOrderMapper).insert(any(ShopOrder.class));
        inOrder.verify(pointsFeignClient).deductPoints(anyLong(), anyInt(), anyString(),
                nullable(Long.class), anyString());
    }

    @Test
    void exchangeOrder_shouldRecordCompensationAndFail_whenDeductTimeoutUnknown() {
        // 扣分结果不明（超时/网络异常）：必须抛业务异常回滚本地事务，
        // 并写入补偿台账（POINTS_EXCHANGE_ROLLBACK）以便核对并退还可能已被扣走的积分。
        Medicine medicine = createMedicine();
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 1)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);
        when(pointsFeignClient.deductPoints(anyLong(), anyInt(), anyString(), nullable(Long.class), anyString()))
                .thenThrow(new RuntimeException("Read timed out"));
        when(compensationTaskMapper.insert(any(CompensationTask.class))).thenReturn(1);

        assertThrows(BusinessException.class,
                () -> shopOrderService.exchangeOrder(1L, 1L, 1));

        ArgumentCaptor<CompensationTask> captor = ArgumentCaptor.forClass(CompensationTask.class);
        verify(compensationTaskMapper, times(1)).insert(captor.capture());
        CompensationTask task = captor.getValue();
        assertEquals("POINTS_EXCHANGE_ROLLBACK", task.getBizType());
        assertEquals(850, readPoints(task.getPayload()));
    }

    @Test
    void exchangeOrder_shouldNotRecordCompensation_whenPointsInsufficient() {
        // 业务性失败（余额不足）：就地回滚即可，无需补偿（积分从未被扣）
        Medicine medicine = createMedicine();
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 1)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);
        when(pointsFeignClient.deductPoints(anyLong(), anyInt(), anyString(), nullable(Long.class), anyString()))
                .thenReturn(Result.error("积分余额不足"));

        assertThrows(BusinessException.class,
                () -> shopOrderService.exchangeOrder(1L, 1L, 1));

        verify(compensationTaskMapper, never()).insert(any(CompensationTask.class));
    }

    @Test
    void exchangeOrder_shouldDeferCompensationUntilAfterRollback() {
        // J-03 的关键一环：补偿台账**不能在事务内写**，否则会被随后的 rollback 一起撤销
        //（那就等于原缺陷：扣了积分却没有任何补偿记录）。
        // 本用例模拟真实事务上下文，验证台账是在 afterCompletion(ROLLED_BACK) 之后才落库。
        Medicine medicine = createMedicine();
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 1)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);
        when(pointsFeignClient.deductPoints(anyLong(), anyInt(), anyString(), nullable(Long.class), anyString()))
                .thenThrow(new RuntimeException("Read timed out"));
        when(compensationTaskMapper.insert(any(CompensationTask.class))).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(BusinessException.class,
                    () -> shopOrderService.exchangeOrder(1L, 1L, 1));

            // 1) 事务尚未结束时，台账**不得**已写入（否则会被回滚撤销）
            verify(compensationTaskMapper, never()).insert(any(CompensationTask.class));

            // 2) 应已注册事务同步回调；模拟回滚完成后，台账才落库
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, syncs.size(), "应注册一个事务同步回调");
            syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            ArgumentCaptor<CompensationTask> captor = ArgumentCaptor.forClass(CompensationTask.class);
            verify(compensationTaskMapper, times(1)).insert(captor.capture());
            assertEquals("POINTS_EXCHANGE_ROLLBACK", captor.getValue().getBizType());
            assertEquals(850, readPoints(captor.getValue().getPayload()));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void exchangeOrder_shouldSkipCompensation_whenTransactionCommitted() {
        // 事务是提交（而非回滚）时不应写补偿台账
        Medicine medicine = createMedicine();
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 1)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);
        when(pointsFeignClient.deductPoints(anyLong(), anyInt(), anyString(), nullable(Long.class), anyString()))
                .thenThrow(new RuntimeException("boom"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(BusinessException.class, () -> shopOrderService.exchangeOrder(1L, 1L, 1));
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            // 模拟事务最终提交 -> 不应写台账
            syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verify(compensationTaskMapper, never()).insert(any(CompensationTask.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ===== J-02 修复的回归防护 =====
    // 缺陷背景：原唯一键 uk_order_request (request_id) 不含 user_id，而幂等查询带 user_id。
    // A 用了 requestId=R 后，B 用同一个 R 时查询查不到 -> 走到 INSERT -> 撞全表唯一键
    // -> createOrder 未捕获 -> 冒泡成系统 500（HTTP 200 + code 500）。
    // 修复：唯一键改为 (user_id, request_id)，并捕获并发撞键返回业务错误。

    @Test
    void createOrder_shouldReturnBusinessError_whenConcurrentDuplicateKey() {
        // 并发双击：两请求同时通过幂等查询，只有一个能插入成功，另一个撞键。
        // 应返回「请勿重复提交」业务错误，而不是冒泡成系统 500。
        Medicine medicine = createMedicine();
        when(shopOrderMapper.selectByRequestId(1L, "req-dup")).thenReturn(null);
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 1)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class)))
                .thenThrow(new DuplicateKeyException("uk_user_order_request"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shopOrderService.createOrder(1L, 1L, 1, null, "req-dup"));
        assertTrue(ex.getMessage().contains("重复提交"),
                "错误信息应提示重复提交，实际: " + ex.getMessage());
    }

    private static int readPoints(String payload) {
        return cn.hutool.json.JSONUtil.parseObj(payload == null ? "{}" : payload).getInt("points", 0);
    }

    // ===== 余额支付（BALANCE）=====

    @Test
    void createOrder_balance_shouldPaySyncAndBecomePaid() {
        Medicine medicine = createMedicine(); // 单价 28.5 × 2 = 应付 57.0
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 2)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);
        when(pointsFeignClient.deductBalance(eq(1L), any(BigDecimal.class), eq("BALANCE_PAY"),
                any(), anyString())).thenReturn(Result.success(true));

        ShopOrder order = shopOrderService.createOrder(1L, 1L, 2, null, "req-b1", "BALANCE");

        assertEquals("BALANCE", order.getPayType());
        assertEquals("PAID", order.getStatus());
        // 扣款金额必须是应付口径（总额-优惠），而不是总额
        verify(pointsFeignClient).deductBalance(eq(1L), eq(new BigDecimal("57.0")),
                eq("BALANCE_PAY"), any(), anyString());
        verify(orderPaymentService).confirmPaid(any());
    }

    @Test
    void createOrder_balance_shouldThrowWithoutConfirm_whenInsufficientBalance() {
        Medicine medicine = createMedicine();
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 1)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);
        when(pointsFeignClient.deductBalance(eq(1L), any(BigDecimal.class), eq("BALANCE_PAY"),
                any(), anyString())).thenReturn(Result.error(400, "余额不足"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shopOrderService.createOrder(1L, 1L, 1, null, "req-b2", "BALANCE"));

        assertTrue(ex.getMessage().contains("余额不足"));
        // 业务失败不应推进支付、不应建补偿台账（扣款明确未发生）
        verify(orderPaymentService, never()).confirmPaid(any());
        verify(compensationTaskMapper, never()).insert(any(CompensationTask.class));
    }

    @Test
    void createOrder_balance_shouldRecordCompensation_whenFeignTimeout() {
        // 结果不明（真超时，Feign 抛异常而非降级返回）：回滚后必须写补偿台账对账退款
        Medicine medicine = createMedicine();
        when(medicineMapper.selectById(1L)).thenReturn(medicine);
        when(medicineService.reduceStock(1L, 2)).thenReturn(true);
        when(shopOrderMapper.insert(any(ShopOrder.class))).thenReturn(1);
        when(pointsFeignClient.deductBalance(eq(1L), any(BigDecimal.class), eq("BALANCE_PAY"),
                any(), anyString())).thenThrow(new RuntimeException("Read timed out"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shopOrderService.createOrder(1L, 1L, 2, null, "req-b3", "BALANCE"));

        assertTrue(ex.getMessage().contains("支付服务暂时不可用"));
        ArgumentCaptor<CompensationTask> captor = ArgumentCaptor.forClass(CompensationTask.class);
        verify(compensationTaskMapper).insert(captor.capture());
        CompensationTask task = captor.getValue();
        assertEquals("BALANCE_PAY_ROLLBACK", task.getBizType());
        assertEquals(1L, task.getUserId());
        assertTrue(task.getPayload().contains("57.0"), "payload 应记录扣款金额: " + task.getPayload());
    }

    @Test
    void cancelOrder_balance_shouldRefundBalanceAndRecordRefundInfo() {
        ShopOrder order = new ShopOrder();
        order.setId(5L);
        order.setUserId(1L);
        order.setPayType("BALANCE");
        order.setStatus("PAID");
        order.setMedicineId(1L);
        order.setQuantity(2);
        order.setTotalAmount(new BigDecimal("57.0"));

        when(shopOrderMapper.selectById(5L)).thenReturn(order);
        when(shopOrderMapper.cancelIfNotCancelled(5L)).thenReturn(1);
        when(medicineService.restoreStock(1L, 2)).thenReturn(true);
        when(pointsFeignClient.refundBalance(eq(1L), any(BigDecimal.class), eq("BALANCE_REFUND"),
                eq(5L), anyString())).thenReturn(Result.success(true));

        assertTrue(shopOrderService.cancelOrder(5L, 1L));

        verify(pointsFeignClient).refundBalance(eq(1L), eq(new BigDecimal("57.0")),
                eq("BALANCE_REFUND"), eq(5L), anyString());
        verify(shopOrderMapper).updateRefundInfo(eq(5L), eq(new BigDecimal("57.0")), any(LocalDateTime.class));
    }

    // ===== 管理员退款（adminRefund）=====

    @Test
    void adminRefund_shouldReuseUserRefundChain() {
        // 管理员代退款：跳过归属校验，但复用同一退款链路与幂等护栏（原子抢占）
        ShopOrder order = new ShopOrder();
        order.setId(6L);
        order.setUserId(2L);
        order.setPayType("BALANCE");
        order.setStatus("PAID");
        order.setMedicineId(1L);
        order.setQuantity(1);
        order.setTotalAmount(new BigDecimal("9.90"));

        when(shopOrderMapper.selectById(6L)).thenReturn(order);
        when(shopOrderMapper.cancelIfNotCancelled(6L)).thenReturn(1);
        when(medicineService.restoreStock(1L, 1)).thenReturn(true);
        when(pointsFeignClient.refundBalance(eq(2L), any(BigDecimal.class), eq("BALANCE_REFUND"),
                eq(6L), anyString())).thenReturn(Result.success(true));

        assertTrue(shopOrderService.adminRefund(6L));

        verify(pointsFeignClient).refundBalance(eq(2L), eq(new BigDecimal("9.90")),
                eq("BALANCE_REFUND"), eq(6L), anyString());
        verify(shopOrderMapper).updateRefundInfo(eq(6L), eq(new BigDecimal("9.90")), any(LocalDateTime.class));
    }

    @Test
    void adminRefund_shouldReject_whenOrderNotPaid() {
        ShopOrder order = new ShopOrder();
        order.setId(7L);
        order.setUserId(2L);
        order.setStatus("PENDING");
        when(shopOrderMapper.selectById(7L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shopOrderService.adminRefund(7L));
        assertTrue(ex.getMessage().contains("仅已支付订单可退款"));
        // 未付款订单不存在"退款"，任何退回动作都不应发生
        verify(shopOrderMapper, never()).cancelIfNotCancelled(anyLong());
    }

    // ===== AI 订单查询：条件必须落在 SQL 里（不能在调用方拿一页数据再筛） =====

    @Test
    void searchOrders_refundedAlias_becomesCancelledAndRefundAmountConditionInSql() {
        stubEmptyPage();
        shopOrderService.searchOrders(7L, "REFUNDED", null, null, null, 1, 10);

        LambdaQueryWrapper<ShopOrder> wrapper = capturedSearchWrapper();
        String sql = wrapper.getSqlSegment();
        // 「已退款」= CANCELLED 且 refund_amount > 0：两个条件都必须在 SQL 里，
        // 否则"我有哪些已退款"只能筛最近一页，更早的退款单永远查不到
        assertTrue(sql.contains("status =") && sql.contains("refund_amount >"),
                "已退款必须等价于 status=CANCELLED AND refund_amount>0: " + sql);
        // 状态是参数绑定（不是把 CANCELLED 拼进 SQL）：值应出现在绑定参数里
        assertTrue(wrapper.getParamNameValuePairs().containsValue("CANCELLED"),
                wrapper.getParamNameValuePairs().toString());
    }

    @Test
    void searchOrders_plainStatus_staysEqualityWithoutRefundCondition() {
        stubEmptyPage();
        shopOrderService.searchOrders(7L, "PAID", null, null, null, 1, 10);

        LambdaQueryWrapper<ShopOrder> wrapper = capturedSearchWrapper();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("status =") && !sql.contains("refund_amount"),
                "普通状态查询不该带退款条件: " + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("PAID"),
                wrapper.getParamNameValuePairs().toString());
    }

    @Test
    void orderSummary_refundedAlias_usesTheSamePushedDownCondition() {
        when(shopOrderMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        shopOrderService.orderSummary(7L, "REFUNDED", null, null);

        ArgumentCaptor<Wrapper<ShopOrder>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(shopOrderMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("refund_amount >"),
                "汇总与列表必须用同一个条件构造: " + captor.getValue().getSqlSegment());
    }

    /** selectPage 的空页桩：Mockito 不会自动填充分页对象，需手动回填 records */
    private void stubEmptyPage() {
        when(shopOrderMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<ShopOrder> page = invocation.getArgument(0);
            page.setRecords(new java.util.ArrayList<>());
            return page;
        });
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<ShopOrder> capturedSearchWrapper() {
        ArgumentCaptor<Wrapper<ShopOrder>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(shopOrderMapper).selectPage(any(), captor.capture());
        return (LambdaQueryWrapper<ShopOrder>) captor.getValue();
    }
}
