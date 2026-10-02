package com.chronic.shop.service;

import com.chronic.shop.vo.CartItemVO;

import java.util.List;

public interface CartService {

    /**
     * 加入购物车：同一用户同一药品重复加购自动合并数量（上限 99）
     */
    void addItem(Long userId, Long medicineId, Integer quantity);

    /**
     * 我的购物车（按加入时间倒序）：带药品现价/图片/库存，
     * 下架或库存不足的条目标记 invalid=true，前端置灰
     */
    List<CartItemVO> listCart(Long userId);

    /**
     * 修改数量（1~99）；条目必须属于本人
     */
    void updateQuantity(Long userId, Long itemId, Integer quantity);

    /**
     * 删除一条（条目必须属于本人）
     */
    void removeItem(Long userId, Long itemId);

    /**
     * 清空我的购物车
     */
    void clearCart(Long userId);

    /**
     * 删除指定条目（结算成功后清已结算项用）：只删属于该用户的，归属不符的静默忽略
     */
    void deleteItems(Long userId, List<Long> itemIds);
}
