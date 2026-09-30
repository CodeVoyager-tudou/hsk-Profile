package com.chronic.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.shop.entity.ShopOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 订单表 Mapper
 *
 * @author chronic
 */
@Mapper
public interface ShopOrderMapper extends BaseMapper<ShopOrder> {

    /**
     * 原子取消：仅当订单未被取消时置为 CANCELLED，返回受影响行数。
     * 并发取消时只有一个请求能抢占成功，防止库存/优惠券/积分被重复退回。
     */
    @Update("UPDATE shop_order SET status = 'CANCELLED' WHERE id = #{orderId} AND status != 'CANCELLED'")
    int cancelIfNotCancelled(@Param("orderId") Long orderId);

    /**
     * 幂等键查询：同一用户同一 requestId 只应存在一单
     */
    @Select("SELECT * FROM shop_order WHERE user_id = #{userId} AND request_id = #{requestId} LIMIT 1")
    ShopOrder selectByRequestId(@Param("userId") Long userId, @Param("requestId") String requestId);

    /**
     * 原子支付确认：只有 PENDING 才能推进到 PAID，并发/重复回调只有一个生效（幂等的第一道闸）
     */
    @Update("UPDATE shop_order SET status = 'PAID', pay_time = NOW() WHERE id = #{orderId} AND status = 'PENDING'")
    int confirmPaid(@Param("orderId") Long orderId);

    /**
     * 原子关单：仅 PENDING 可关闭（避免把已支付订单关掉）
     */
    @Update("UPDATE shop_order SET status = 'CANCELLED' WHERE id = #{orderId} AND status = 'PENDING'")
    int cancelPending(@Param("orderId") Long orderId);

    /**
     * 标记积分已发放（幂等：只从待发放改为已发放，重复执行不再影响数据）
     */
    @Update("UPDATE shop_order SET points_status = 1 WHERE id = #{orderId} AND points_status = 0")
    int markPointsGranted(@Param("orderId") Long orderId);

    /**
     * 超时未支付的订单（用于关单并归还库存/优惠券）
     */
    @Select("SELECT * FROM shop_order WHERE status = 'PENDING' AND create_time < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE) "
            + "ORDER BY id LIMIT #{limit}")
    List<ShopOrder> selectExpiredPending(@Param("minutes") int minutes, @Param("limit") int limit);

    /**
     * 只更新退款台账两列（J-05 修复）。
     *
     * <p><b>为什么需要它，而不是直接 {@code updateById(order)}：</b></p>
     * <p>{@code cancelOrder} 里的 {@code order} 是取消**之前**读出来的快照，
     * 它的 {@code status} 还是 {@code PAID}（或 {@code PENDING}）；
     * 而 {@code cancelIfNotCancelled} 已经在数据库里把状态改成了 {@code CANCELLED}。
     * 此时如果调用 {@code updateById(order)}，MyBatis-Plus 会把实体里所有非 null 字段
     * 一起写回去 —— 包括那个陈旧的 {@code status}，把 {@code CANCELLED} **覆盖回 {@code PAID}**。</p>
     * <p>后果非常严重：{@code cancelIfNotCancelled} 的条件是 {@code status != 'CANCELLED'}，
     * 状态被改回 PAID 之后这个条件每次都成立，于是
     * <b>同一张订单可以被无限次取消</b>，每次都退还库存和优惠券。</p>
     * <p>改成只更新退款相关的两列，从根上避免写回陈旧字段。
     * {@code AND status = 'CANCELLED'} 是额外保证：只对确实已取消的订单记账。</p>
     */
    @Update("UPDATE shop_order SET refund_amount = #{refundAmount}, refund_time = #{refundTime} "
            + "WHERE id = #{orderId} AND status = 'CANCELLED'")
    int updateRefundInfo(@Param("orderId") Long orderId,
                         @Param("refundAmount") java.math.BigDecimal refundAmount,
                         @Param("refundTime") java.time.LocalDateTime refundTime);
}
