package com.chronic.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单明细：一笔订单里每个药品一行（名称/单价是<b>下单时的快照</b>，
 * 药品后来改价/改名不影响历史订单的对账口径）。
 *
 * <p>SINGLE（单商品直购）单也写一行明细——取消/关单退库存统一按明细循环，
 * 不用为"有没有明细"写两套逻辑；CART（购物车合并结算）单天然多行。</p>
 *
 * <p>秒杀单是直插 shop_order 的历史路径，没有明细行，退库存走主表单商品字段兜底。</p>
 */
@Data
@TableName("shop_order_item")
public class ShopOrderItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long medicineId;

    private String medicineName;

    private BigDecimal unitPrice;

    private Integer quantity;

    /** 小计 = 单价 x 数量 */
    private BigDecimal subtotal;

    /**
     * 药品图片 URL（非表字段）：明细只存名称/单价快照不存图，
     * 查询接口按 medicineId 批量回填，CART 单前端按明细渲染商品图。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String imageUrl;
}
