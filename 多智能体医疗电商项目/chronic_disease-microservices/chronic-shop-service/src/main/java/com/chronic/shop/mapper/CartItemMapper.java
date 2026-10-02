package com.chronic.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.shop.entity.CartItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {

    /**
     * 加购合并（幂等 upsert）：同一用户对同一药品只会有一行（唯一键 uk_user_medicine）。
     * 再次加购不是插新行，而是把数量累加上去，并用 LEAST 封顶 99——
     * 与下单数量上限 MAX_QUANTITY 一致，防止反复点加购把数量顶到几万。
     *
     * <p>并发重复加购也安全：两条 INSERT 同时到，唯一键保证只有一行，
     * 后到的那条走 ON DUPLICATE KEY UPDATE 分支累加数量。</p>
     *
     * @return 受影响行数（upsert 语义下插入和更新都返回非 0，调用方无需关心）
     */
    @Insert("INSERT INTO cart_item (user_id, medicine_id, quantity, create_time, update_time) "
            + "VALUES (#{userId}, #{medicineId}, #{quantity}, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE quantity = LEAST(quantity + #{quantity}, 99), update_time = NOW()")
    int upsertAdd(@Param("userId") Long userId,
                  @Param("medicineId") Long medicineId,
                  @Param("quantity") Integer quantity);
}
