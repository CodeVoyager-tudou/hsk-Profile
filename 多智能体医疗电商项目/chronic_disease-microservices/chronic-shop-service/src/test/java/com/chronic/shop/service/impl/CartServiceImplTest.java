package com.chronic.shop.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.chronic.common.exception.BusinessException;
import com.chronic.shop.entity.CartItem;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.mapper.CartItemMapper;
import com.chronic.shop.mapper.MedicineMapper;
import com.chronic.shop.vo.CartItemVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 购物车服务单测：加购合并、归属校验（IDOR）、失效标记。
 */
@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    private CartItemMapper cartItemMapper;

    @Mock
    private MedicineMapper medicineMapper;

    private CartServiceImpl cartService;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CartItem.class);
    }

    @BeforeEach
    void setUp() {
        cartService = new CartServiceImpl(cartItemMapper, medicineMapper);
    }

    private Medicine medicine(long id, String name, String price, int stock, int status) {
        Medicine m = new Medicine();
        m.setId(id);
        m.setName(name);
        m.setPrice(new BigDecimal(price));
        m.setStock(stock);
        m.setStatus(status);
        return m;
    }

    private CartItem cartItem(long id, long userId, long medicineId, int quantity) {
        CartItem item = new CartItem();
        item.setId(id);
        item.setUserId(userId);
        item.setMedicineId(medicineId);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void addItem_shouldUpsertAndMerge() {
        when(medicineMapper.selectById(1L)).thenReturn(medicine(1L, "阿司匹林", "12.00", 50, 1));

        cartService.addItem(1L, 1L, 3);

        // 合并逻辑在 SQL 里（ON DUPLICATE KEY UPDATE），这里只验证把参数原样交给了 upsert
        verify(cartItemMapper, times(1)).upsertAdd(1L, 1L, 3);
    }

    @Test
    void addItem_shouldThrow_whenQuantityOutOfRange() {
        assertThrows(BusinessException.class, () -> cartService.addItem(1L, 1L, 0));
        assertThrows(BusinessException.class, () -> cartService.addItem(1L, 1L, 100));
        verify(cartItemMapper, never()).upsertAdd(anyLong(), anyLong(), anyInt());
    }

    @Test
    void addItem_shouldThrow_whenMedicineOffShelf() {
        when(medicineMapper.selectById(1L)).thenReturn(medicine(1L, "阿司匹林", "12.00", 50, 0));

        assertThrows(BusinessException.class, () -> cartService.addItem(1L, 1L, 1));
        verify(cartItemMapper, never()).upsertAdd(anyLong(), anyLong(), anyInt());
    }

    @Test
    void listCart_shouldAssemblePriceAndMarkInvalid() {
        // 条目 1：正常（库存 50 够买 2）；条目 2：库存只有 1 却想买 3 → invalid
        CartItem ok = cartItem(11L, 1L, 1L, 2);
        CartItem overStock = cartItem(12L, 1L, 2L, 3);
        when(cartItemMapper.selectList(any())).thenReturn(List.of(ok, overStock));
        when(medicineMapper.selectBatchIds(any())).thenReturn(List.of(
                medicine(1L, "阿司匹林", "12.00", 50, 1),
                medicine(2L, "布洛芬", "19.90", 1, 1)));

        List<CartItemVO> vos = cartService.listCart(1L);

        assertEquals(2, vos.size());
        CartItemVO okVo = vos.get(0);
        assertEquals("阿司匹林", okVo.getMedicineName());
        assertEquals(0, new BigDecimal("24.00").compareTo(okVo.getSubtotal()));
        assertFalse(okVo.getInvalid());
        CartItemVO overVo = vos.get(1);
        assertTrue(overVo.getInvalid(), "库存不足的条目应标记 invalid 让前端置灰");
        assertTrue(overVo.getStock() < overVo.getQuantity());
    }

    @Test
    void updateQuantity_shouldThrow_whenNotOwner() {
        when(cartItemMapper.selectById(11L)).thenReturn(cartItem(11L, 2L, 1L, 1));

        assertThrows(BusinessException.class, () -> cartService.updateQuantity(1L, 11L, 5));
        verify(cartItemMapper, never()).updateById(any(CartItem.class));
    }

    @Test
    void removeItem_shouldThrow_whenNotOwner() {
        when(cartItemMapper.selectById(11L)).thenReturn(cartItem(11L, 2L, 1L, 1));

        assertThrows(BusinessException.class, () -> cartService.removeItem(1L, 11L));
        verify(cartItemMapper, never()).deleteById(anyLong());
    }

    @Test
    void clearCart_shouldDeleteOnlyMine() {
        cartService.clearCart(1L);
        // delete(wrapper) 的 wrapper 里带 user_id 条件（这里只验证确实执行了删除）
        verify(cartItemMapper, times(1)).delete(any());
    }
}
