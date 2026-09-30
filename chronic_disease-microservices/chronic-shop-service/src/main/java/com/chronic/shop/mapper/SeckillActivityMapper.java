package com.chronic.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.shop.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀活动 Mapper
 *
 * @author chronic
 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 原子扣秒杀名额：已售 + 购买数 ≤ 总名额才生效，防并发超卖
     * （Redis 预扣之后的数据库层兜底，方向是「宁可少卖不超卖」）
     */
    @Update("UPDATE seckill_activity SET sold_count = sold_count + #{qty} "
            + "WHERE id = #{id} AND status = 1 AND sold_count + #{qty} <= total_stock")
    int increaseSold(@Param("id") Long id, @Param("qty") Integer qty);

    /**
     * 回退秒杀名额：关单（超时未支付 / 取消）时把已售减回去。
     * 带 {@code sold_count >= qty} 守卫，任何异常路径都不会把计数减成负数。
     */
    @Update("UPDATE seckill_activity SET sold_count = sold_count - #{qty} "
            + "WHERE id = #{id} AND sold_count >= #{qty}")
    int decreaseSold(@Param("id") Long id, @Param("qty") Integer qty);

    /**
     * 活动上下架（管理端）
     */
    @Update("UPDATE seckill_activity SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 调整活动时间窗口（管理端）：开始必须早于结束，SQL 层再兜一道
     */
    @Update("UPDATE seckill_activity SET start_time = #{startTime}, end_time = #{endTime} "
            + "WHERE id = #{id} AND #{startTime} < #{endTime}")
    int updateTime(@Param("id") Long id,
                   @Param("startTime") java.time.LocalDateTime startTime,
                   @Param("endTime") java.time.LocalDateTime endTime);
}
