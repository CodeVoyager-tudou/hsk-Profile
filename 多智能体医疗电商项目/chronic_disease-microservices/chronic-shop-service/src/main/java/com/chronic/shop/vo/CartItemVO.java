package com.chronic.shop.vo;

import com.chronic.shop.entity.CartItem;
import com.chronic.shop.entity.Medicine;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 购物车条目视图（带药品现价/图片/库存等实时信息）。
 *
 * <p>购物车表里只存 medicine_id + 数量，价格等展示信息每次都从 medicine 表现查——
 * 商家改价、改库存后购物车显示的永远是"现在买是什么价"，结算也按现价算，
 * 不存在"购物车价格过期导致少收钱"的问题。</p>
 */
@Data
public class CartItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long medicineId;

    private String medicineName;

    private String imageUrl;

    /** 现价（medicine.price） */
    private BigDecimal price;

    private Integer quantity;

    /** 小计 = 现价 x 数量 */
    private BigDecimal subtotal;

    /** 当前库存 */
    private Integer stock;

    /** 药品是否上架 */
    private Boolean onSale;

    /**
     * 是否不可结算（下架、删除或库存不足该数量）。
     * 前端据此置灰勾选框并提示；结算接口会再校验一遍，这里只是提前告知。
     */
    private Boolean invalid;

    private LocalDateTime createTime;

    public static CartItemVO of(CartItem item, Medicine medicine) {
        CartItemVO vo = new CartItemVO();
        vo.setId(item.getId());
        vo.setMedicineId(item.getMedicineId());
        vo.setCreateTime(item.getCreateTime());
        vo.setQuantity(item.getQuantity());
        if (medicine == null) {
            // 药品已被删除：前端置灰展示，结算时同样会被拒绝
            vo.setMedicineName("该商品已不存在");
            vo.setInvalid(true);
            vo.setOnSale(false);
            return vo;
        }
        vo.setMedicineName(medicine.getName());
        vo.setImageUrl(medicine.getImageUrl());
        vo.setPrice(medicine.getPrice());
        vo.setStock(medicine.getStock());
        boolean onSale = medicine.getStatus() != null && medicine.getStatus() == 1;
        vo.setOnSale(onSale);
        int stock = medicine.getStock() == null ? 0 : medicine.getStock();
        boolean invalid = !onSale || stock < item.getQuantity();
        vo.setInvalid(invalid);
        vo.setSubtotal(medicine.getPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity())));
        return vo;
    }
}
