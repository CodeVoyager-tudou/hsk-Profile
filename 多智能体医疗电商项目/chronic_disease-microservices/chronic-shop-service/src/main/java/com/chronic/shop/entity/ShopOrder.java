package com.chronic.shop.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("shop_order")
public class ShopOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    /** 客户端幂等键（X-Request-Id）：同一用户重复提交只会生成一单 */
    private String requestId;

    private Long userId;

    /**
     * 订单类型: SINGLE-单商品直购 CART-购物车合并结算。
     * CART 单的药品信息全在明细表 shop_order_item 里，主表的 medicine_id 等单商品字段
     * 只作可读兜底（medicineName 存"购物车结算(N件)"摘要）。
     */
    private String orderType;

    private Long medicineId;

    private String medicineName;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal totalAmount;

    private Integer pointsEarned;

    /** 积分发放状态: 0-待发放(待补偿) 1-已发放 */
    private Integer pointsStatus;

    /** 支付方式: CASH-现金 POINTS-积分兑换 */
    private String payType;

    /** 使用的用户优惠券ID(user_coupon.id) */
    private Long couponId;

    /** 优惠券抵扣金额 */
    private BigDecimal discountAmount;

    /** 积分兑换消耗的积分 */
    private Integer pointsUsed;

    /** 退款金额（取消现金订单时按应付金额回填） */
    private BigDecimal refundAmount;

    /** 退款时间 */
    private LocalDateTime refundTime;

    /** 状态: PENDING-待支付 PAID-已支付 CANCELLED-已取消 */
    private String status;

    /** 支付成功时间（渠道回调确认时写入） */
    private LocalDateTime payTime;

    /**
     * 秒杀单来源活动 id（seckill_activity.id）；普通单为 null。
     * 秒杀单是「待支付现金单」，超时关单时据此把秒杀名额连同"已抢用户"一起释放，
     * 否则每次未支付都会永久吃掉一个名额。
     */
    private Long seckillActivityId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 药品图片 URL（非表字段）：订单表只存药品名与药品号，不存图。
     * 列表/详情接口按 medicineId 回填，让订单缩略图与商城页取到同一张图
     * （前端 orderImg 优先读它，为空才退回按名称生成的占位图）。
     */
    @TableField(exist = false)
    private String medicineImage;

    /**
     * 订单明细（非表字段）：查询接口按 order_id 回填。
     * CART（购物车）单前端展示以此为准；SINGLE 单也有一行，展示逻辑可以统一。
     */
    @TableField(exist = false)
    private java.util.List<ShopOrderItem> items;
}
