package com.chronic.shop.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.chronic.shop.entity.ShopOrder;

import java.util.List;

public interface ShopOrderService extends IService<ShopOrder> {

    /**
     * 下单(可叠加优惠券), 购买后获得积分奖励 —— 内部/测试重载，默认 CASH
     */
    ShopOrder createOrder(Long userId, Long medicineId, Integer quantity, Long userCouponId);

    /**
     * 现金下单 + 客户端幂等键：同一用户重复提交（双击/网络重试）只会生成一单
     */
    ShopOrder createOrder(Long userId, Long medicineId, Integer quantity, Long userCouponId, String requestId);

    /**
     * 下单（指定支付方式）：CASH-现金（模拟渠道） / BALANCE-余额支付。
     * <p>余额支付为同步链路：建单(PENDING) → 事务内 Feign 扣余额 → confirmPaid 推进 PAID；
     * 余额不足则整单回滚（库存/订单/优惠券一并撤销），用户立即可见失败原因。</p>
     */
    ShopOrder createOrder(Long userId, Long medicineId, Integer quantity, Long userCouponId,
                          String requestId, String payType);

    /**
     * 购物车合并结算：勾选的购物车条目（可多件不同药品）合成<b>一笔</b>订单。
     *
     * <p>这是满减券真正"用得上"的关键：门槛按多件商品的合计金额判定，
     * 而不是单商品小计——单买一件 12 元的药够不着"满 30 减 5"，
     * 购物车里再凑两件就够了。券仍核销到这一笔订单上（user_coupon 一券一单的约束不变）。</p>
     *
     * <p>事务内一次完成：校验条目归属 → 逐件扣库存（任一不足整体回滚）→ 按现价合计
     * → 用券 → 建单(order_type=CART) + 写明细 → 清掉已结算的购物车条目 → 走与直购
     * 完全相同的支付链路（CASH 挂超时消息 / BALANCE 同步扣款）。</p>
     *
     * @param userId       登录用户（网关注入，购物车条目归属校验依据）
     * @param itemIds      勾选的购物车条目 id（cart_item.id）
     * @param userCouponId 用户优惠券 id，可空
     * @param requestId    幂等键（X-Request-Id），与直购共用 shop_order(user_id, request_id) 唯一键
     * @param payType      CASH / BALANCE
     */
    ShopOrder createCartOrder(Long userId, java.util.List<Long> itemIds, Long userCouponId,
                              String requestId, String payType);

    /**
     * 收银台确认支付（PENDING 单统一入口，按 payType 分派扣款）：
     * BALANCE 此刻扣余额、POINTS 此刻扣积分、CASH 走模拟渠道，全部推进 PAID。
     * 业务失败（余额/积分不足）如实报错，订单保持 PENDING 可重试或等超时关单。
     */
    ShopOrder payPendingOrder(Long orderId, Long userId);

    /**
     * 建单后启动支付：模拟渠道即时确认 → 立即推进 PAID；真实渠道 → 保持 PENDING，
     * 并挂上「支付超时关单」延迟消息（30 分钟窗口 + 2 分钟宽限）。
     *
     * <p>秒杀链路也走这个方法，<b>不要</b>在别处再写一套支付策略：
     * 两处各写一份，迟早会因为改了其中一处而行为不一致。</p>
     */
    void beginPaymentAfterCreated(ShopOrder order);

    /**
     * 积分兑换下单(建 PENDING 单，收银台确认支付时才扣积分；不参与优惠券和积分奖励)
     */
    ShopOrder exchangeOrder(Long userId, Long medicineId, Integer quantity);

    /**
     * 取消订单（校验归属 + 原子抢占取消，防并发双退；现金单回收已发放积分）
     */
    boolean cancelOrder(Long orderId, Long userId);

    /**
     * 管理员退款（管理端）：仅 PAID 订单，复用用户取消的退款链路，不做归属校验
     */
    boolean adminRefund(Long orderId);

    /**
     * 关闭超时未支付订单（PENDING 超时 -> CANCELLED，归还库存与优惠券），由定时任务调用
     */
    int closeExpiredPendingOrders();

    /**
     * 支付超时延迟消息的处理入口（两段式，由 OrderPayTimeoutConsumer 调用）：
     * <ul>
     *   <li>phase 1（下单后 30 分钟到点）：仍 PENDING → 发第二段宽限消息（2 分钟）；
     *       已支付/已取消 → 幂等跳过。宽限期吸收"展示窗口最后一秒点的支付"的在途请求。</li>
     *   <li>phase 2（再过 2 分钟 = 下单后 32 分钟到点）：真正的关单时点，
     *       CAS 关单（只关 PENDING，归还库存与优惠券）；已支付/已取消 → 幂等跳过。</li>
     * </ul>
     * 与 {@link #closeExpiredPendingOrders()} 的扫描式关单互补：延迟消息精确触发为主，
     * xxl-job 扫表作为漏发消息时的兜底，两条路径共用同一原子关单 SQL，天然幂等。
     */
    void handlePayTimeout(Long orderId, int phase);

    /**
     * 查询本人订单详情（非本人访问抛业务异常）
     */
    ShopOrder getOrderForUser(Long orderId, Long userId);

    /**
     * 按订单号查询本人订单（供 AI 订单查询等内部调用：用户手里只有 32 位订单号，没有主键 id）。
     * 归属校验口径与 {@link #getOrderForUser} 一致：非本人一律"订单不存在"。
     */
    ShopOrder getOrderByNoForUser(String orderNo, Long userId);

    /**
     * 分页查询用户订单列表
     */
    Page<ShopOrder> listByUser(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 回填订单上的药品图片（实体非表字段）：订单表不存图，不补这一步
     * 订单缩略图就只能退回占位图。一次批量查药品，不逐条查库。
     */
    void enrichMedicineImage(List<ShopOrder> orders);

    /**
     * 按条件搜索用户订单（供 AI 问答等内部调用）：状态 / 药品名关键词 / 下单时间区间。
     *
     * <p>条件全部下推到 SQL（MyBatis-Plus 条件构造器，参数绑定），不在调用方拿一页数据再筛——
     * 否则"我 3 月买过什么"这类问题只能在最近一页里找，答案是错的。</p>
     *
     * <p>{@code status} 除 PENDING/PAID/CANCELLED 外还接受查询别名 {@code REFUNDED}
     * （= CANCELLED 且 refund_amount &gt; 0，见实现类的注释）。</p>
     */
    Page<ShopOrder> searchOrders(Long userId, String status, String keyword,
                                 java.time.LocalDateTime startTime, java.time.LocalDateTime endTime,
                                 Integer pageNum, Integer pageSize);

    /**
     * 订单汇总（供 AI 回答"我这个月花了多少/多少笔"）：总笔数、各状态笔数、实付金额合计、积分消耗合计。
     * 同样是条件下推 SQL，返回聚合结果而非明细。{@code status} 同样支持 REFUNDED 别名。
     */
    java.util.Map<String, Object> orderSummary(Long userId, String status,
                                               java.time.LocalDateTime startTime,
                                               java.time.LocalDateTime endTime);

    /**
     * 全站订单统计（管理端）：总单数、今日单数、已支付单数、成交额（已支付合计）、
     * 待支付/已取消数量、已退款金额。
     *
     * <p>刻意只在管理端暴露：网关对 {@code /api/admin/**} 有 ADMIN 角色闸门，
     * 而 AI 问答是所有登录用户可用的，不该让普通用户问到全站 GMV。</p>
     */
    java.util.Map<String, Object> adminStats();
}
