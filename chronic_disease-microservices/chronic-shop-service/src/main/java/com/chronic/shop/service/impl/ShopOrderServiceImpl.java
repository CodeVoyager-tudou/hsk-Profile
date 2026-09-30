package com.chronic.shop.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.CompensationTask;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.entity.UserCoupon;
import com.chronic.shop.feign.PointsFeignClient;
import com.chronic.shop.mapper.CompensationTaskMapper;
import com.chronic.shop.mapper.MedicineMapper;
import com.chronic.shop.mapper.ShopOrderMapper;
import com.chronic.shop.mapper.UserCouponMapper;
import com.chronic.shop.mq.OrderEventPublisher;
import com.chronic.shop.mq.PayTimeoutPublisher;
import com.chronic.shop.pay.OrderPaymentService;
import com.chronic.shop.pay.PaymentGateway;
import com.chronic.shop.service.CouponService;
import com.chronic.shop.service.MedicineService;
import com.chronic.shop.service.SeckillSlotService;
import com.chronic.shop.service.ShopOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单服务实现 —— 本项目业务最复杂的一个类，建议配合下面的「白话版」阅读。
 *
 * <h3>【小白先看：这个类管什么】</h3>
 * 商城里「买药」有两种方式，都从这里下单：
 * <ol>
 *   <li><b>现金买</b>：花钱买药，付完款送积分（积分以后能换药）。</li>
 *   <li><b>积分兑换</b>：直接用积分换药，不花钱。</li>
 * </ol>
 * 另外还有「取消订单」「超时未付款自动关单」。
 *
 * <h3>【两个必须理解的词】</h3>
 * <ul>
 *   <li><b>幂等（idempotent）</b>：同一个操作执行一次和执行多次，结果一样。
 *       用户手抖双击「立即购买」，不能变成两张订单、扣两次库存。
 *       做法：前端每次下单带一个唯一编号（X-Request-Id），
 *       数据库对 (user_id, request_id) 建唯一索引，重复的插不进去。</li>
 *   <li><b>补偿台账（compensation_task）</b>：下单要同时改两个服务的数据
 *       —— 本地库（订单/库存）和积分服务（扣/加分）。这两个库不在同一个事务里，
 *       中间任何一步失败都可能对不上账。所以一旦跨服务调用失败，
 *       就把「欠这一笔」记到补偿台账里，由定时任务后面重试，直到成功或转人工。</li>
 * </ul>
 *
 * <h3>【三条主流程（注意执行顺序，顺序本身就是正确性的一部分）】</h3>
 * <ul>
 *   <li><b>现金购买</b>：幂等校验 → 扣库存 → 校验并核销优惠券 → 建单(PENDING)
 *       → 事务提交后由支付渠道推进 PAID → 支付成功才发积分（失败留待补偿）
 *       与发订单事件。</li>
 *   <li><b>余额支付</b>：幂等校验 → 扣库存 → 核销优惠券 → 建单(PENDING)
 *       → <b>事务内</b> Feign 扣余额 → confirmPaid 推进 PAID（发积分 + outbox）。
 *       余额是我们自己的账本，无外部渠道回调，同步支付即可；失败分支与积分兑换同构
 *       （业务失败回滚 / 结果不明写 BALANCE_PAY_ROLLBACK 补偿台账）。</li>
 *   <li><b>积分兑换</b>：扣库存 → <b>先建单</b>(PAID) → <b>再扣积分</b>。
 *       顺序很关键：如果反过来（先扣分再建单），一旦建单失败回滚，
 *       积分已经被扣走且没有任何记录可追溯 —— 用户的积分就凭空消失了。
 *       现在的顺序保证任何失败都能「按订单号」找回这笔账。</li>
 *   <li><b>取消订单</b>：原子抢占取消（防并发重复退）→ 退库存
 *       → 退优惠券 / 退积分 / 退余额；跨服务失败则写补偿台账。</li>
 *   <li><b>超时关单</b>：PENDING 超过 N 分钟自动关掉，归还库存与优惠券。</li>
 * </ul>
 *
 * @author chronic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopOrderServiceImpl extends ServiceImpl<ShopOrderMapper, ShopOrder> implements ShopOrderService {

    private static final int MAX_QUANTITY = 99;

    private final MedicineService medicineService;
    private final MedicineMapper medicineMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponService couponService;
    private final PointsFeignClient pointsFeignClient;
    private final CompensationTaskMapper compensationTaskMapper;
    private final PaymentGateway paymentGateway;
    private final OrderPaymentService orderPaymentService;
    private final OrderEventPublisher orderEventPublisher;
    private final PayTimeoutPublisher payTimeoutPublisher;
    /** 秒杀单（CASH PENDING）被关单/取消时释放名额：不释放就会每笔未支付都永久吃掉一个名额 */
    private final SeckillSlotService seckillSlotService;

    /** 待支付订单超时关单时间（分钟） */
    @Value("${chronic.pay.pending-timeout-minutes:30}")
    private int pendingTimeoutMinutes;

    /** 单轮最多处理多少个超时订单 */
    private static final int CLOSE_BATCH_LIMIT = 100;

    /** 查询别名（非表状态值）：已退款 = CANCELLED 且 refund_amount > 0 */
    public static final String REFUNDED_STATUS_ALIAS = "REFUNDED";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShopOrder createOrder(Long userId, Long medicineId, Integer quantity, Long userCouponId) {
        return createOrder(userId, medicineId, quantity, userCouponId, null, "CASH");
    }

    /**
     * 现金购买下单（带客户端幂等键）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShopOrder createOrder(Long userId, Long medicineId, Integer quantity, Long userCouponId, String requestId) {
        return createOrder(userId, medicineId, quantity, userCouponId, requestId, "CASH");
    }

    /**
     * 下单（指定支付方式）
     *
     * <p><b>CASH</b>：建单 PENDING → 事务提交后由模拟渠道推进（即时确认或等回调）。</p>
     *
     * <p><b>BALANCE</b>：余额是我们自己账本上的钱，不存在"外部渠道异步回调"，
     * 所以走<b>同步支付</b>（与积分兑换 J-03 修复后的模式同构）：
     * 建单 PENDING → 事务内 Feign 扣余额 → confirmPaid 推进 PAID。
     * 余额不足等业务失败会抛异常回滚整个本地事务（库存/订单/优惠券一并撤销），
     * 用户立即看到明确原因；扣款结果不明（超时）则回滚后写补偿台账对账退款。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShopOrder createOrder(Long userId, Long medicineId, Integer quantity, Long userCouponId,
                                 String requestId, String payType) {
        String payTypeNormalized = normalizePayType(payType);
        // 0. 幂等：同一用户同一 requestId 重复提交（双击/前端重试）直接返回原单，不重复扣库存/用券
        if (requestId != null && !requestId.trim().isEmpty()) {
            ShopOrder existing = baseMapper.selectByRequestId(userId, requestId.trim());
            if (existing != null) {
                log.info("重复提交命中幂等键，返回原订单: userId={}, requestId={}, orderNo={}",
                        userId, requestId, existing.getOrderNo());
                return existing;
            }
        }
        checkQuantity(quantity);
        Medicine medicine = getOnSaleMedicine(medicineId);
        // 原子扣库存，防超卖
        medicineService.reduceStock(medicineId, quantity);

        BigDecimal unitPrice = medicine.getPrice();
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        // pointsReward 未配置按 0 处理，避免 NPE
        Integer pointsEarned = Optional.ofNullable(medicine.getPointsReward()).orElse(0) * quantity;

        ShopOrder order = new ShopOrder();
        order.setOrderNo(IdUtil.fastSimpleUUID());
        order.setRequestId(requestId == null ? null : requestId.trim());
        order.setUserId(userId);
        order.setMedicineId(medicineId);
        order.setMedicineName(medicine.getName());
        order.setQuantity(quantity);
        order.setUnitPrice(unitPrice);
        order.setTotalAmount(totalAmount);
        order.setPointsEarned(pointsEarned);
        order.setPointsStatus(0);
        order.setPayType(payTypeNormalized);
        // 先落 PENDING：现金单由渠道回调推进；余额单在扣款成功后由 confirmPaid 原子推进
        order.setStatus("PENDING");

        // 使用优惠券，计算抵扣金额
        if (userCouponId != null) {
            BigDecimal discount = checkCoupon(userCouponId, userId, totalAmount);
            order.setCouponId(userCouponId);
            order.setDiscountAmount(discount);
        }
        try {
            save(order);
        } catch (DuplicateKeyException e) {
            // J-02：唯一键已改为 (user_id, request_id)，正常情况下不会撞键
            //（前面已查过同用户同 requestId）。此处兜住并发双击：两请求同时通过幂等查询，
            // 只有一个能插入成功，另一个撞键 —— 返回友好业务错误，而不是冒泡成系统 500。
            log.warn("并发重复提交（幂等键撞键）: userId={}, requestId={}", userId, order.getRequestId());
            throw new BusinessException("请勿重复提交，订单已存在");
        }
        // 原子核销优惠券
        if (userCouponId != null) {
            couponService.markUsed(userCouponId, userId, order.getId());
        }
        if ("BALANCE".equals(payTypeNormalized)) {
            // 余额支付：事务内同步扣款 + 推进 PAID（见方法注释）
            payWithBalance(order);
            return order;
        }
        // 支付确认放到事务提交之后：既不占用数据库事务，也保证订单已可见
        beginPaymentAfterCreated(order);
        return order;
    }

    /**
     * 建单后启动支付（见接口注释）。秒杀链路也调用这里，保证"新单开始计时"只有一条实现。
     */
    @Override
    public void beginPaymentAfterCreated(ShopOrder order) {
        afterCommit(() -> startPayment(order));
    }

    /** 支付方式归一化：只接受 CASH / BALANCE（POINTS 走独立的 exchangeOrder 链路） */
    private String normalizePayType(String payType) {
        if (payType == null || payType.trim().isEmpty()) {
            return "CASH";
        }
        String normalized = payType.trim().toUpperCase();
        if (!"CASH".equals(normalized) && !"BALANCE".equals(normalized)) {
            throw new BusinessException("不支持的支付方式: " + payType);
        }
        return normalized;
    }

    /** 应付金额 = 总额 - 优惠抵扣（余额扣款与退款都用这个口径） */
    private BigDecimal payableAmount(ShopOrder order) {
        return Optional.ofNullable(order.getTotalAmount()).orElse(BigDecimal.ZERO)
                .subtract(Optional.ofNullable(order.getDiscountAmount()).orElse(BigDecimal.ZERO));
    }

    /**
     * 余额支付（调用方处于 createOrder 事务内）：
     * Feign 扣余额（幂等唯一键）→ confirmPaid 推进 PAID + 发积分 + 写 outbox。
     *
     * <p>失败分支与积分兑换完全同构：
     * ①业务失败（余额不足/降级返回非200）→ 抛异常回滚本地事务，扣款要么没发生要么明确失败；
     * ②结果不明（Feign 真超时/本地推进失败）→ 回滚后写补偿台账 BALANCE_PAY_ROLLBACK，
     * 由 CompensationRetryJob 核对订单状态并退还多扣的余额。</p>
     */
    private void payWithBalance(ShopOrder order) {
        BigDecimal payable = payableAmount(order);
        String remark = "余额支付 " + order.getMedicineName() + " x" + order.getQuantity();
        try {
            Result<Boolean> deductResult = pointsFeignClient.deductBalance(order.getUserId(), payable,
                    "BALANCE_PAY", order.getId(), remark);
            if (deductResult == null || deductResult.getCode() == null || deductResult.getCode() != 200) {
                String msg = deductResult == null ? "支付服务不可用" : deductResult.getMessage();
                throw new BusinessException(msg != null ? msg : "余额扣款失败");
            }
        } catch (BusinessException e) {
            // 业务性失败：就地回滚整个事务（库存/订单/优惠券一并撤销），余额未被扣走
            throw e;
        } catch (Exception e) {
            // 非业务性异常（超时/网络）：无法确定余额是否已扣 —— 必须在事务回滚之后写补偿台账
            //（事务内直接 INSERT 会随回滚一起消失，正是 J-03 的原缺陷）
            recordCompensationAfterRollback("BALANCE_PAY_ROLLBACK", order, payable,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw new BusinessException("支付服务暂时不可用，请稍后重试");
        }
        // 扣款已成功。此后任何本地失败（概率极低）都必须退还余额，否则钱扣了单没了
        try {
            ShopOrder paid = orderPaymentService.confirmPaid(order.getId());
            if (paid != null) {
                order.setStatus(paid.getStatus());
                order.setPayTime(paid.getPayTime());
                order.setPointsStatus(paid.getPointsStatus());
            }
        } catch (Exception e) {
            recordCompensationAfterRollback("BALANCE_PAY_ROLLBACK", order, payable,
                    "confirmPaid失败:" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            throw new BusinessException("订单支付确认失败，已扣款项将在对账后退回");
        }
    }

    /**
     * 继续支付待支付订单（PENDING 现金单）：归属校验 + 状态校验后走支付确认。
     * 余额订单为同步支付（成功即 PAID、失败即回滚），不存在 PENDING 态。
     */
    @Override
    public ShopOrder payPendingOrder(Long orderId, Long userId) {
        ShopOrder order = getOrderForUser(orderId, userId);
        if (!"PENDING".equals(order.getStatus())) {
            throw new BusinessException("订单当前状态不可支付: " + order.getStatus());
        }
        if (!"CASH".equals(order.getPayType())) {
            throw new BusinessException("该订单不支持收银台支付: " + order.getPayType());
        }
        return orderPaymentService.confirmPaid(orderId);
    }

    /**
     * 启动支付：模拟渠道即时确认 -> 直接推进 PAID；真实渠道 -> 保持 PENDING 等回调，
     * 并发出「支付超时」延迟消息——30 分钟（可配）后由消费者检查关单
     */
    private void startPayment(ShopOrder order) {
        if (!paymentGateway.confirmImmediately()) {
            log.info("订单待支付: orderNo={}, userId={}, payUrl={}",
                    order.getOrderNo(), order.getUserId(), paymentGateway.createPayUrl(order));
            // 下单时挂上超时消息：chain 模式发第一段（30m）；timer 模式发单条定时消息（+32m 关单）。
            // 此处运行在事务提交后的 afterCommit 回调里，订单已落库可见，发消息无时序问题。
            payTimeoutPublisher.publishOrderTimeout(order.getId(), order.getOrderNo());
            return;
        }
        // 与 createOrder 是同一个对象，这里把支付结果同步回该实例，调用方直接拿到最终状态
        ShopOrder paid = orderPaymentService.confirmPaid(order.getId());
        if (paid != null) {
            order.setStatus(paid.getStatus());
            order.setPayTime(paid.getPayTime());
            order.setPointsStatus(paid.getPointsStatus());
        }
    }

    /**
     * 积分兑换下单
     *
     * <p><b>J-03 修复</b>：原实现把「远程扣积分」放在本地事务**内部**，且 Feign readTimeout=3s。
     * 于是存在这样的窗口：扣分已在 points-service 生效，但本地事务随后回滚
     * （库存不足、订单写库失败、连接超时导致异常）——订单没了、库存归还了、
     * 而积分**已经被扣走且补偿台账为空**（该分支只在扣分返回非 200 时建账，
     * 抛异常时事务回滚会连台账一起回滚），造成积分永久丢失。</p>
     *
     * <p>修复策略：**先扣积分，成功后再落订单**。这样任何失败路径都只可能「没扣分」，
     * 不存在「扣了分却没有订单」。若后续落单失败，则写补偿台账把积分退回。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShopOrder exchangeOrder(Long userId, Long medicineId, Integer quantity) {
        checkQuantity(quantity);
        Medicine medicine = getOnSaleMedicine(medicineId);
        if (medicine.getPointsPrice() == null || medicine.getPointsPrice() <= 0) {
            throw new BusinessException("该药品不支持积分兑换");
        }
        int pointsNeeded = medicine.getPointsPrice() * quantity;

        // 1) 先扣库存（本地、原子），失败直接抛错，此时尚未扣积分
        medicineService.reduceStock(medicineId, quantity);

        ShopOrder order = new ShopOrder();
        order.setOrderNo(IdUtil.fastSimpleUUID());
        order.setUserId(userId);
        order.setMedicineId(medicineId);
        order.setMedicineName(medicine.getName());
        order.setQuantity(quantity);
        order.setUnitPrice(medicine.getPrice());
        order.setTotalAmount(medicine.getPrice().multiply(BigDecimal.valueOf(quantity)));
        order.setPointsEarned(0);
        order.setPayType("POINTS");
        order.setPointsUsed(pointsNeeded);
        order.setDiscountAmount(BigDecimal.ZERO);
        // 积分兑换在下单时即完成扣分，故直接置 PAID
        order.setStatus("PAID");
        order.setPayTime(LocalDateTime.now());
        save(order);

        // 2) 落单后再扣积分：此时订单已存在，扣分失败可按订单号精确补偿（积分不会被吞）
        //    远程业务异常返回 HTTP 200 + code!=200，必须显式检查
        try {
            Result<Boolean> deductResult = pointsFeignClient.deductPoints(userId, pointsNeeded,
                    "POINTS_EXCHANGE", order.getId(), "积分兑换 " + medicine.getName() + " x" + quantity);
            if (deductResult == null || deductResult.getCode() == null || deductResult.getCode() != 200) {
                String msg = deductResult == null ? "积分服务不可用" : deductResult.getMessage();
                throw new BusinessException(msg != null ? msg : "积分扣减失败");
            }
        } catch (BusinessException e) {
            // 业务性失败（如余额不足）：就地回滚整个事务，库存与订单一并撤销，无需补偿
            throw e;
        } catch (Exception e) {
            // 非业务性异常（超时/网络/熔断）：**无法确定积分是否已扣**。
            // 抛错回滚本地事务（撤销订单与库存），并记补偿台账以便对账，
            // 补偿任务的语义是「按订单核对并退还多扣的积分」。
            //
            // 注意：此处**不能**直接调 recordCompensation —— 它会在当前事务内 INSERT，
            // 而紧接着的 throw 会回滚事务，把台账一起撤销（正是原缺陷的翻版）。
            // 必须注册到事务回滚之后才写入。
            recordCompensationAfterRollback("POINTS_EXCHANGE_ROLLBACK", order, pointsNeeded,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw new BusinessException("积分服务暂时不可用，请稍后重试");
        }
        // J-14：在**当前事务内**把订单事件写入 outbox（与订单同生共死），
        // 真正投递交给 OrderEventRelayJob。
        orderEventPublisher.enqueue(order);
        log.info("积分兑换订单创建成功: orderNo={}, userId={}, pointsUsed={}",
                order.getOrderNo(), userId, pointsNeeded);
        return order;
    }

    /**
     * 取消订单（用户侧）：归属校验后走统一退款链路
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelOrder(Long orderId, Long userId) {
        ShopOrder order = getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        // 归属校验：禁止取消他人订单
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作他人订单");
        }
        return cancelInternal(orderId, "user:" + userId);
    }

    /**
     * 管理员退款（管理端）：仅 PAID 订单，复用用户取消的同一退款链路
     * （退余额/退积分/退库存/退券 + 幂等抢占），只是不做归属校验——
     * 归属校验的目的是防用户互操作，管理员代处理本来就要跨过它；
     * 权限由网关 /api/admin/** 的 ADMIN 角色闸门 + 审计表保证。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean adminRefund(Long orderId) {
        ShopOrder order = getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!"PAID".equals(order.getStatus())) {
            throw new BusinessException("仅已支付订单可退款，当前状态: " + order.getStatus());
        }
        return cancelInternal(orderId, "admin");
    }

    /**
     * 取消退款统一主体（须在事务内）：原子抢占取消（防并发重复退）后按支付方式分别退回。
     *
     * @param operator 操作者标识（审计与日志用，如 "user:123" / "admin"）
     */
    private boolean cancelInternal(Long orderId, String operator) {
        ShopOrder order = getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        // 原子取消：并发请求只有一个能抢占成功，防止库存/优惠券/积分被重复退回
        if (baseMapper.cancelIfNotCancelled(orderId) == 0) {
            throw new BusinessException("订单已取消");
        }
        // 退还库存
        medicineService.restoreStock(order.getMedicineId(), order.getQuantity());
        // 秒杀单：名额也一并释放（普通单无此字段，内部直接跳过）
        releaseSeckillSlotIfAny(order);
        // 根据支付方式分别处理
        if ("CASH".equals(order.getPayType())) {
            // 现金订单：退还优惠券（传 orderId，J-06 会校验「本单用过」且「券未过期」）
            if (order.getCouponId() != null) {
                couponService.markUnused(order.getCouponId(), order.getId());
            }
            // 只有已支付的订单才谈得上回收积分（PENDING 关单不会有积分发放）
            if ("PAID".equals(order.getStatus())
                    && order.getPointsEarned() != null && order.getPointsEarned() > 0) {
                try {
                    Result<Boolean> revokeResult = pointsFeignClient.deductPoints(order.getUserId(),
                            order.getPointsEarned(), "ORDER_CANCEL_REVOKE", order.getId(), "取消订单回收积分奖励");
                    if (revokeResult == null || revokeResult.getCode() == null || revokeResult.getCode() != 200) {
                        recordCompensation("ORDER_POINTS_REVOKE", order, order.getPointsEarned(),
                                revokeResult == null ? "积分服务不可用" : revokeResult.getMessage());
                    }
                } catch (Exception e) {
                    recordCompensation("ORDER_POINTS_REVOKE", order, order.getPointsEarned(), e.getMessage());
                }
            }
            // 退款记账：按应付金额（总额-优惠）回填退款信息，同一事务内落库。
            // 当前未接入真实支付渠道，金额仅作台账记录；对接支付渠道后由渠道回调推进真实打款
            BigDecimal refundAmount = Optional.ofNullable(order.getTotalAmount()).orElse(BigDecimal.ZERO)
                    .subtract(Optional.ofNullable(order.getDiscountAmount()).orElse(BigDecimal.ZERO));
            if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
                // J-05 修复：这里**绝不能**用 updateById(order)。
                // order 是取消前的快照，status 还是旧值；而 cancelIfNotCancelled 刚把库里
                // 改成了 CANCELLED。整体写回会把 status 覆盖回 PAID/PENDING，
                // 导致这单可以被**反复取消**，每次都退库存与优惠券（无限放大）。
                // 改为只更新退款两列。
                baseMapper.updateRefundInfo(orderId, refundAmount, LocalDateTime.now());
            }
        } else if ("POINTS".equals(order.getPayType()) && order.getPointsUsed() != null && order.getPointsUsed() > 0) {
            // 积分订单：退还积分，失败写入补偿台账由定时任务重试
            try {
                Result<Boolean> refundResult = pointsFeignClient.refundPoints(order.getUserId(),
                        order.getPointsUsed(), "POINTS_REFUND", order.getId(), "兑换取消退回积分");
                if (refundResult == null || refundResult.getCode() == null || refundResult.getCode() != 200) {
                    recordCompensation("POINTS_REFUND", order, order.getPointsUsed(),
                            refundResult == null ? "积分服务不可用" : refundResult.getMessage());
                }
            } catch (Exception e) {
                recordCompensation("POINTS_REFUND", order, order.getPointsUsed(), e.getMessage());
            }
        } else if ("BALANCE".equals(order.getPayType())) {
            // 余额订单：退还优惠券（余额单可用券）；已支付的把应付金额退回余额账户
            if (order.getCouponId() != null) {
                couponService.markUnused(order.getCouponId(), order.getId());
            }
            if ("PAID".equals(order.getStatus())) {
                // 余额单不存在 PENDING 态（同步支付：成功即 PAID、失败即整单回滚），
                // 走到这里必然是已扣款的 PAID 单，退款金额 = 应付金额
                BigDecimal payable = payableAmount(order);
                try {
                    Result<Boolean> refundResult = pointsFeignClient.refundBalance(order.getUserId(), payable,
                            "BALANCE_REFUND", order.getId(), "订单取消退回余额");
                    if (refundResult == null || refundResult.getCode() == null || refundResult.getCode() != 200) {
                        recordCompensation("BALANCE_REFUND", order, payable,
                                refundResult == null ? "支付服务不可用" : refundResult.getMessage());
                    }
                } catch (Exception e) {
                    recordCompensation("BALANCE_REFUND", order, payable, e.getMessage());
                }
                baseMapper.updateRefundInfo(orderId, payable, LocalDateTime.now());
            }
        }
        log.info("订单已取消: orderNo={}, payType={}, operator={}", order.getOrderNo(), order.getPayType(), operator);
        return true;
    }

    /**
     * 关单/取消后释放秒杀名额；普通单该字段为 null，直接跳过。
     *
     * <p>为什么必须做：秒杀单是「待支付现金单」，用户不付款被关单时若不释放名额，
     * {@code sold_count} 与 Redis 名额都不会回退 —— 每笔未支付都永久吃掉一个名额。</p>
     */
    private void releaseSeckillSlotIfAny(ShopOrder order) {
        if (order.getSeckillActivityId() != null) {
            seckillSlotService.releaseSlot(order.getSeckillActivityId(), order.getUserId());
        }
    }

    /**
     * 按条件搜索用户订单（见接口注释）：条件全部下推 SQL，参数由条件构造器绑定。
     */
    @Override
    public Page<ShopOrder> searchOrders(Long userId, String status, String keyword,
                                        LocalDateTime startTime, LocalDateTime endTime,
                                        Integer pageNum, Integer pageSize) {
        Page<ShopOrder> page = new Page<>(pageNum == null ? 1 : pageNum,
                pageSize == null ? 10 : Math.min(Math.max(pageSize, 1), 50));
        LambdaQueryWrapper<ShopOrder> wrapper = buildSearchWrapper(userId, status, startTime, endTime)
                .orderByDesc(ShopOrder::getCreateTime);
        if (keyword != null && !keyword.isBlank()) {
            // 药品名模糊匹配：交给 SQL 的 LIKE（参数化），不要在 Java 里把整页拉出来过滤
            wrapper.like(ShopOrder::getMedicineName, keyword.trim());
        }
        baseMapper.selectPage(page, wrapper);
        enrichMedicineImage(page.getRecords());
        return page;
    }

    /**
     * 订单汇总（见接口注释）：只查聚合需要的列，金额与笔数在内存里按状态归类。
     */
    @Override
    public Map<String, Object> orderSummary(Long userId, String status,
                                            LocalDateTime startTime, LocalDateTime endTime) {
        List<ShopOrder> rows = baseMapper.selectList(buildSearchWrapper(userId, status, startTime, endTime)
                .select(ShopOrder::getStatus, ShopOrder::getTotalAmount,
                        ShopOrder::getRefundAmount, ShopOrder::getPointsUsed));
        int total = rows.size();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        BigDecimal paidAmount = BigDecimal.ZERO;
        BigDecimal refundedAmount = BigDecimal.ZERO;
        int pointsUsed = 0;
        for (ShopOrder o : rows) {
            byStatus.merge(o.getStatus() == null ? "UNKNOWN" : o.getStatus(), 1, Integer::sum);
            if ("PAID".equals(o.getStatus())) {
                paidAmount = paidAmount.add(Optional.ofNullable(o.getTotalAmount()).orElse(BigDecimal.ZERO));
            }
            refundedAmount = refundedAmount.add(Optional.ofNullable(o.getRefundAmount()).orElse(BigDecimal.ZERO));
            pointsUsed += Optional.ofNullable(o.getPointsUsed()).orElse(0);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalOrders", total);
        summary.put("byStatus", byStatus);
        summary.put("paidAmount", paidAmount);
        summary.put("refundedAmount", refundedAmount);
        summary.put("pointsUsed", pointsUsed);
        return summary;
    }

    /**
     * 全站订单统计（管理端，见接口注释）。
     *
     * <p>复核 P2-6：旧实现把全表行取回 Java 内存归类，订单量增长时管理端首页线性变慢。
     * 现改为按 status 分组、聚合在 SQL 侧完成（一条 GROUP BY + 一条"今日单数"的 COUNT），
     * 返回字段与口径与旧实现完全一致。</p>
     *
     * <p><b>与 {@link #orderSummary} 的口径差异（有意保留，写下来防止"对不上账"的误解）：</b>
     * <ul>
     *   <li>{@code gmv} 只累计 PAID 单的 total_amount；{@code refundedAmount} 是<b>全表</b>退款合计
     *       （orderSummary 里是过滤窗口内的退款合计）；</li>
     *   <li>本方法不含 {@code pointsUsed}（管理端大盘用不到）；</li>
     *   <li>orderSummary 面向"某个用户某段时间"，本方法面向"全站当前"，两者数字天然不同。</li>
     * </ul></p>
     *
     * <p><b>快照边界（复核第 2 轮 P2-7）：</b>GROUP BY 聚合与"今日单数"的 COUNT 是**两次独立查询**，
     * 并发下单时可能落在两次查询之间，于是 {@code totalOrders} 与 {@code todayOrders} 可能相差
     * 一笔新单（旧实现全表取回一次反而天然同快照）。管理端统计页可接受；<b>不要拿这两个数做减法</b>
     * （如"今日以前的单数"），需要严格一致的口径应在一条 SQL 里完成。</p>
     */
    @Override
    public Map<String, Object> adminStats() {
        List<Map<String, Object>> rows = baseMapper.selectMaps(new QueryWrapper<ShopOrder>()
                .select("status",
                        "COUNT(*) AS status_count",
                        "SUM(total_amount) AS status_amount",
                        "SUM(refund_amount) AS refund_sum")
                .groupBy("status"));

        int total = 0;
        int paidCount = 0;
        int pendingCount = 0;
        int cancelledCount = 0;
        BigDecimal gmv = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            String status = String.valueOf(row.getOrDefault("status", "UNKNOWN"));
            long count = row.get("status_count") == null ? 0 : Long.parseLong(String.valueOf(row.get("status_count")));
            BigDecimal statusAmount = decimalOf(row.get("status_amount"));
            BigDecimal refundSum = decimalOf(row.get("refund_sum"));
            total += (int) count;
            // 用传统 switch 而不是箭头语法：本工程 source/target 是 11，
            // switch 规则（case X ->）是 Java 14+ 才有的，写了编译不过
            switch (status) {
                case "PAID":
                    paidCount += (int) count;
                    gmv = gmv.add(statusAmount);
                    break;
                case "PENDING":
                    pendingCount += (int) count;
                    break;
                case "CANCELLED":
                    cancelledCount += (int) count;
                    break;
                default:
                    // 未知状态只进 total，不进分状态计数（与旧实现一致）
                    break;
            }
            refunded = refunded.add(refundSum);
        }

        // "今日单数"带时间边界，用一条条件 COUNT 下推，不在 select 表达式里拼日期字符串
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        Long todayCount = baseMapper.selectCount(
                new LambdaQueryWrapper<ShopOrder>().gt(ShopOrder::getCreateTime, todayStart));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalOrders", total);
        stats.put("todayOrders", todayCount == null ? 0 : todayCount.intValue());
        stats.put("paidOrders", paidCount);
        stats.put("pendingOrders", pendingCount);
        stats.put("cancelledOrders", cancelledCount);
        stats.put("gmv", gmv);
        stats.put("refundedAmount", refunded);
        return stats;
    }

    /** MySQL 的 SUM 对空组返回 NULL、数值以 DECIMAL 返回，统一转 BigDecimal（空为 0） */
    private BigDecimal decimalOf(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    /**
     * 订单搜索的公共条件（用户 + 状态 + 时间区间），条件构造器绑定参数，无字符串拼接。
     *
     * <p>{@code status} 额外接受查询别名 {@code REFUNDED}：它<b>不是</b>表里的状态值，
     * 而是「CANCELLED 且 refund_amount &gt; 0」的等价条件。放在这里下推 SQL 而不是让调用方
     * 取一页回来在内存里筛，是因为"我有哪些已退款"一旦只筛最近 50 笔，更早的退款就永远查不到。</p>
     */
    private LambdaQueryWrapper<ShopOrder> buildSearchWrapper(Long userId, String status,
                                                             LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<ShopOrder> wrapper = new LambdaQueryWrapper<ShopOrder>()
                .eq(ShopOrder::getUserId, userId);
        if (status != null && !status.isBlank()) {
            String wanted = status.trim().toUpperCase();
            if (REFUNDED_STATUS_ALIAS.equals(wanted)) {
                wrapper.eq(ShopOrder::getStatus, "CANCELLED")
                        .gt(ShopOrder::getRefundAmount, BigDecimal.ZERO);
            } else {
                wrapper.eq(ShopOrder::getStatus, wanted);
            }
        }
        if (startTime != null) {
            wrapper.ge(ShopOrder::getCreateTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(ShopOrder::getCreateTime, endTime);
        }
        return wrapper;
    }

    /**
     * 关闭超时未支付订单：归还库存与优惠券（幂等：只有仍为 PENDING 的单会被关掉）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int closeExpiredPendingOrders() {
        List<ShopOrder> expired = baseMapper.selectExpiredPending(pendingTimeoutMinutes, CLOSE_BATCH_LIMIT);
        int closed = 0;
        for (ShopOrder order : expired) {
            if (closeSinglePendingOrder(order)) {
                closed++;
            }
        }
        if (closed > 0) {
            log.warn("本轮关闭超时未支付订单 {} 笔（超过 {} 分钟未支付）", closed, pendingTimeoutMinutes);
        }
        return closed;
    }

    /**
     * 关闭一笔仍处于 PENDING 的订单（归还库存与优惠券）。
     * 幂等闸门是 {@code cancelPending} 的「status = 'PENDING'」条件：
     * 延迟消息到达时订单可能已被支付/取消，抢占失败即直接忽略，
     * 不会出现「已支付订单被超时关单又退款」的竞态。
     *
     * @return true = 本这次调用完成了关单；false = 订单已是 PAID/CANCELLED，跳过
     */
    private boolean closeSinglePendingOrder(ShopOrder order) {
        // 防御：超时关单只针对现金 PENDING 单（余额单无 PENDING 态、积分单建单即 PAID）。
        // 本方法只归还库存与优惠券、不退任何资金——若未来引入"非现金 PENDING"
        // （如异步银行渠道的余额单），直接关单会吞掉用户资产，必须先走对应退款链路。
        if (!"CASH".equals(order.getPayType())) {
            log.warn("关单跳过（非现金 PENDING 单不适用超时关单，需人工/补偿介入）: orderNo={}, payType={}",
                    order.getOrderNo(), order.getPayType());
            return false;
        }
        if (baseMapper.cancelPending(order.getId()) == 0) {
            log.info("关单跳过（订单状态已非 PENDING）: orderNo={}, status={}",
                    order.getOrderNo(), order.getStatus());
            return false;
        }
        medicineService.restoreStock(order.getMedicineId(), order.getQuantity());
        if (order.getCouponId() != null) {
            // 同样传 orderId：只有当这张券确实由本单占用、且仍未过期时才退回（J-06）
            couponService.markUnused(order.getCouponId(), order.getId());
        }
        releaseSeckillSlotIfAny(order);
        log.info("超时未支付订单已关闭: orderNo={}, userId={}", order.getOrderNo(), order.getUserId());
        return true;
    }

    /**
     * 支付超时延迟消息的处理入口（两段式，由 OrderPayTimeoutConsumer 调用）：
     * 展示给用户的支付窗口是 30 分钟，真实关单时点是 32 分钟——多出的 2 分钟宽限期
     * 专门吸收"用户在展示窗口最后一秒点支付、请求仍在途"的情况（详见
     * PayTimeoutPublisher 的类注释）。已支付/已取消的订单在任何阶段都幂等跳过。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handlePayTimeout(Long orderId, int phase) {
        ShopOrder order = getById(orderId);
        if (order == null) {
            log.warn("[支付超时] 订单不存在，跳过: orderId={}, phase={}", orderId, phase);
            return;
        }
        if (phase <= 1) {
            // 第一段（30m）到点：不关单，仍 PENDING 就挂第二段宽限消息（2m 后真正关单）
            if ("PENDING".equals(order.getStatus())) {
                log.info("[支付超时] 展示窗口到期仍未支付，进入宽限期（第二段延迟消息已发出）: orderNo={}",
                        order.getOrderNo());
                payTimeoutPublisher.publishGrace(orderId, order.getOrderNo());
            } else {
                log.info("[支付超时] 展示窗口到期时订单已完结，跳过: orderNo={}, status={}",
                        order.getOrderNo(), order.getStatus());
            }
            return;
        }
        // 第二段（32m）到点：真正的关单时点（CAS 只关 PENDING，已支付/已取消幂等跳过）
        closeSinglePendingOrder(order);
    }

    /**
     * 查询本人订单详情：非本人访问直接拒绝，防止越权拉取他人订单
     */
    @Override
    public ShopOrder getOrderForUser(Long orderId, Long userId) {
        ShopOrder order = getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        // 详情页（含收银台）也要显示药品图，同样补一次
        enrichMedicineImage(java.util.Collections.singletonList(order));
        return order;
    }

    /**
     * 按订单号查询本人订单（订单号是 32 位十六进制串，不是主键 id）。
     * 归属校验与 getOrderForUser 一致：查不到或不属于该用户，一律回"订单不存在"，
     * 不泄露"这个订单号是否存在"。
     */
    @Override
    public ShopOrder getOrderByNoForUser(String orderNo, Long userId) {
        if (orderNo == null || orderNo.isBlank() || userId == null) {
            throw new BusinessException("订单不存在");
        }
        ShopOrder order = getOne(new LambdaQueryWrapper<ShopOrder>()
                .eq(ShopOrder::getOrderNo, orderNo.trim()), false);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException("订单不存在");
        }
        enrichMedicineImage(java.util.Collections.singletonList(order));
        return order;
    }

    /**
     * 分页查询用户订单列表
     */
    @Override
    public Page<ShopOrder> listByUser(Long userId, Integer pageNum, Integer pageSize) {
        Page<ShopOrder> page = new Page<>(pageNum == null ? 1 : pageNum,
                pageSize == null ? 10 : Math.min(Math.max(pageSize, 1), 100));
        LambdaQueryWrapper<ShopOrder> wrapper = new LambdaQueryWrapper<ShopOrder>()
                .eq(ShopOrder::getUserId, userId)
                .orderByDesc(ShopOrder::getCreateTime);
        baseMapper.selectPage(page, wrapper);
        enrichMedicineImage(page.getRecords());
        return page;
    }

    /**
     * 回填订单上的药品图片（见接口注释）：按 medicineId 去重批量查，不逐条查库。
     * 关掉这条路径（或药品被删）时字段保持为空，前端自动退回占位图，不影响下单/支付。
     */
    @Override
    public void enrichMedicineImage(List<ShopOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        List<Long> medicineIds = orders.stream()
                .map(ShopOrder::getMedicineId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (medicineIds.isEmpty()) {
            return;
        }
        Map<Long, Medicine> byId = medicineMapper.selectBatchIds(medicineIds).stream()
                .collect(Collectors.toMap(Medicine::getId, Function.identity(), (a, b) -> a));
        for (ShopOrder order : orders) {
            Medicine medicine = byId.get(order.getMedicineId());
            if (medicine != null) {
                order.setMedicineImage(medicine.getImageUrl());
            }
        }
    }

    /**
     * 校验购买数量：1~99 之间
     */
    private void checkQuantity(Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BusinessException("购买数量必须在 1~" + MAX_QUANTITY + " 之间");
        }
    }

    /**
     * 查询药品并校验上架状态
     */
    private Medicine getOnSaleMedicine(Long medicineId) {
        Medicine medicine = medicineMapper.selectById(medicineId);
        if (medicine == null || medicine.getStatus() == 0) {
            throw new BusinessException("药品不存在或已下架");
        }
        return medicine;
    }

    /**
     * 用券前置校验（归属/状态/有效期/门槛），返回抵扣金额；最终核销由 markUsed 原子完成
     */
    private BigDecimal checkCoupon(Long userCouponId, Long userId, BigDecimal totalAmount) {
        UserCoupon userCoupon = userCouponMapper.selectById(userCouponId);
        if (userCoupon == null || !userId.equals(userCoupon.getUserId())) {
            throw new BusinessException("优惠券不存在");
        }
        if (!"UNUSED".equals(userCoupon.getStatus())) {
            throw new BusinessException("优惠券已使用或已过期");
        }
        if (userCoupon.getExpireTime() != null && userCoupon.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("优惠券已过期");
        }
        com.chronic.shop.entity.Coupon coupon = couponService.getById(userCoupon.getCouponId());
        if (coupon == null) {
            throw new BusinessException("优惠券活动不存在");
        }
        if (totalAmount.compareTo(coupon.getThresholdAmount()) < 0) {
            throw new BusinessException("订单金额未满足用券门槛(满" + coupon.getThresholdAmount() + "可用)");
        }
        // 抵扣不超过订单金额，防止券面额配置错误导致负应付
        BigDecimal discount = coupon.getDiscountAmount();
        return discount.compareTo(totalAmount) > 0 ? totalAmount : discount;
    }

    /**
     * 跨服务调用失败写入补偿台账：定时任务按退避策略重试，超过上限转 FAILED 并告警
     *
     * <p><b>J-04 修复</b>：唯一键已由「(biz_type, biz_id) 全状态唯一」改为
     * 「(biz_type, biz_id, active_flag) 仅约束 PENDING」（见 V6 迁移），
     * 因此 FAILED / DONE 的历史行**不再占用唯一性**，同一笔业务可重新建账。
     * 撞键只剩一种含义：**已有一条在途（PENDING）任务**，此时跳过是正确的（防重复建账）。</p>
     */
    private void recordCompensation(String bizType, ShopOrder order, Integer points, String error) {
        // 积分类 payload 保持历史格式（数值，Job 按 getInt 解析）：{"points":50}
        recordCompensation(bizType, order, points == null ? "0" : String.valueOf(points), "points", error);
    }

    /** 余额类补偿：payload 记金额（字符串，元），与积分类字段区分：{"amount":"19.90"} */
    private void recordCompensation(String bizType, ShopOrder order, BigDecimal amount, String error) {
        recordCompensation(bizType, order,
                amount == null ? "0" : "\"" + amount.toPlainString() + "\"", "amount", error);
    }

    /**
     * 补偿台账写入的唯一实现：payload 由调用方给原始 JSON 值片段。
     */
    private void recordCompensation(String bizType, ShopOrder order, String rawValue,
                                    String valueField, String error) {
        log.error("跨服务调用失败，写入补偿台账: bizType={}, 订单号={}, 原因={}", bizType, order.getOrderNo(), error);
        try {
            CompensationTask task = new CompensationTask();
            task.setBizType(bizType);
            task.setBizId(order.getId());
            task.setUserId(order.getUserId());
            task.setPayload("{\"" + valueField + "\":" + rawValue + "}");
            task.setStatus("PENDING");
            task.setRetryCount(0);
            task.setLastError(error == null ? null : error.substring(0, Math.min(error.length(), 500)));
            task.setNextRetryTime(LocalDateTime.now().plusMinutes(1));
            compensationTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 新唯一键只约束 PENDING：撞键说明已有在途任务，跳过即可，不会漏补偿
            log.warn("补偿台账已有在途任务，跳过重复建账: bizType={}, 订单号={}", bizType, order.getOrderNo());
        } catch (Exception e) {
            log.error("补偿台账写入失败（人工介入），订单号={}", order.getOrderNo(), e);
        }
    }

    /**
     * 事务提交后执行；无事务时立即执行
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * 在事务**回滚完成之后**写入补偿台账（J-03 的关键一环）。
     *
     * <p>若在事务内直接 INSERT 再抛异常回滚，台账会被一并撤销 —— 这正是原缺陷
     * 「扣了积分却没有任何补偿记录」的成因。因此必须等回滚结束后再写：
     * 此时事务已结束、连接已释放，mapper 的 INSERT 以自动提交方式执行，能够真正落库。</p>
     *
     * <p>无事务上下文（如单元测试或非事务调用）时立即写入。</p>
     */
    private void recordCompensationAfterRollback(String bizType, ShopOrder order, Integer points, String error) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        try {
                            recordCompensation(bizType, order, points, error);
                        } catch (Exception e) {
                            log.error("补偿台账写入失败，需人工对账: bizType={}, orderNo={}, points={}",
                                    bizType, order.getOrderNo(), points, e);
                        }
                    }
                }
            });
        } else {
            try {
                recordCompensation(bizType, order, points, error);
            } catch (Exception e) {
                log.error("补偿台账写入失败，需人工对账: bizType={}, orderNo={}, points={}",
                        bizType, order.getOrderNo(), points, e);
            }
        }
    }

    /** 余额类版本：事务回滚后写金额型补偿台账（BALANCE_PAY_ROLLBACK 等） */
    private void recordCompensationAfterRollback(String bizType, ShopOrder order, BigDecimal amount, String error) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        try {
                            recordCompensation(bizType, order, amount, error);
                        } catch (Exception e) {
                            log.error("补偿台账写入失败，需人工对账: bizType={}, orderNo={}, amount={}",
                                    bizType, order.getOrderNo(), amount, e);
                        }
                    }
                }
            });
        } else {
            try {
                recordCompensation(bizType, order, amount, error);
            } catch (Exception e) {
                log.error("补偿台账写入失败，需人工对账: bizType={}, orderNo={}, amount={}",
                        bizType, order.getOrderNo(), amount, e);
            }
        }
    }
}
