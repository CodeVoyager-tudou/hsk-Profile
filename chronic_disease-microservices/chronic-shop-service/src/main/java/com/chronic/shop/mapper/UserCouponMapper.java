package com.chronic.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.shop.entity.UserCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 该用户在该活动下已领取的最大序号(0 表示从未领取)。
     * <p>
     * 领取时以 max + 1 作为本次领取序号写入 receive_no，再叠加唯一键
     * uk_user_coupon_user_coupon_no(user_id, coupon_id, receive_no)，
     * 由数据库兜底保证不超过每人限领(并发/多实例/锁失效都不会超领)。
     * 用最大序号而不是行数：行数会被数据清理影响，序号天然代表"领过几次"。
     */
    @Select("SELECT COALESCE(MAX(receive_no), 0) FROM user_coupon "
            + "WHERE user_id = #{userId} AND coupon_id = #{couponId}")
    int selectMaxReceiveNo(@Param("userId") Long userId, @Param("couponId") Long couponId);

    /**
     * 核销:仅本人未使用且未过期的券可核销
     */
    @Update("UPDATE user_coupon SET status = 'USED', use_time = NOW(), order_id = #{orderId} "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 'UNUSED' AND expire_time > NOW()")
    int markUsed(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") Long orderId);

    /**
     * 订单取消时退回券（J-06 修复：与核销做**对称校验**）。
     *
     * <p>原实现是 {@code WHERE id = #{id} AND status = 'USED'}，比核销侧弱得多，
     * 产生两个问题：</p>
     * <ol>
     *   <li><b>不校验有效期</b>：已过期的券会被拉回 {@code UNUSED}，
     *       而 {@code expire_time} 仍是过去时间 —— 变成一张"看起来能用"的过期券，
     *       用户可以继续拿它下单抵扣，形成折扣套利。</li>
     *   <li><b>不校验订单</b>：无法确认这张券确实是被本单用掉的，
     *       缺少与订单的对应关系。</li>
     * </ol>
     * <p>现在加上 {@code order_id} 与 {@code expire_time > NOW()} 两个条件。
     * 调用方在返回 0 行时应改用 {@link #markExpiredIfUsedByOrder} 把过期券置为
     * {@code EXPIRED}，避免留下"USED 但已过期"的脏状态。</p>
     */
    @Update("UPDATE user_coupon SET status = 'UNUSED', use_time = NULL, order_id = NULL "
            + "WHERE id = #{id} AND status = 'USED' AND order_id = #{orderId} "
            + "AND expire_time > NOW()")
    int markUnused(@Param("id") Long id, @Param("orderId") Long orderId);

    /**
     * 订单取消时，若该券确由本单使用但**已过期**，则置为 EXPIRED（J-06）。
     * <p>不能退成 UNUSED（那就成了可用的过期券），也不能留在 USED
     * （前端会把它当作"已使用"而无法区分于正常核销）。</p>
     */
    @Update("UPDATE user_coupon SET status = 'EXPIRED', use_time = NULL, order_id = NULL "
            + "WHERE id = #{id} AND status = 'USED' AND order_id = #{orderId} "
            + "AND expire_time <= NOW()")
    int markExpiredIfUsedByOrder(@Param("id") Long id, @Param("orderId") Long orderId);
}
