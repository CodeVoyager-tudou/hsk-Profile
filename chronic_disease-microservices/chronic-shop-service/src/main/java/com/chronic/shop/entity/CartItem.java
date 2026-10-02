package com.chronic.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 购物车条目：一个用户对同一药品只有一行，重复加购由唯一键 uk_user_medicine
 * 在数据库层合并数量（INSERT ... ON DUPLICATE KEY UPDATE，见 CartItemMapper）。
 *
 * <p>存 MySQL 而不是 Redis：结算时要用它跟库存/优惠券在同一个本地事务里对账，
 * 同库同事务才有一致性；购物车对用户是"要放心"的数据，丢了体验很差。</p>
 */
@Data
@TableName("cart_item")
public class CartItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long medicineId;

    private Integer quantity;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
