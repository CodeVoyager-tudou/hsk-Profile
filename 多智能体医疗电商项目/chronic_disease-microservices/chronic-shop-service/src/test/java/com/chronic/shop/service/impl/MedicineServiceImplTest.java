package com.chronic.shop.service.impl;

import com.chronic.common.exception.BusinessException;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.mapper.MedicineMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicineServiceImplTest {

    @Mock
    private MedicineMapper medicineMapper;

    private MedicineServiceImpl medicineService;

    @BeforeEach
    void setUp() {
        medicineService = new MedicineServiceImpl();
        ReflectionTestUtils.setField(medicineService, "baseMapper", medicineMapper);
    }

    @Test
    void reduceStock_shouldDecreaseStock() {
        when(medicineMapper.reduceStock(1L, 2)).thenReturn(1);

        boolean result = medicineService.reduceStock(1L, 2);
        assertTrue(result);
        verify(medicineMapper, times(1)).reduceStock(1L, 2);
    }

    @Test
    void reduceStock_shouldThrow_whenStockInsufficient() {
        when(medicineMapper.reduceStock(1L, 100)).thenReturn(0);
        Medicine medicine = new Medicine();
        medicine.setStock(10);
        medicine.setStatus(1);
        when(medicineMapper.selectById(1L)).thenReturn(medicine);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> medicineService.reduceStock(1L, 100));
        assertTrue(ex.getMessage().contains("库存不足"));
    }

    @Test
    void reduceStock_shouldThrow_whenMedicineNotOnSale() {
        when(medicineMapper.reduceStock(1L, 1)).thenReturn(0);
        when(medicineMapper.selectById(1L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> medicineService.reduceStock(1L, 1));
        assertEquals("药品不存在或已下架", ex.getMessage());
    }

    @Test
    void reduceStock_shouldThrow_whenMedicineOffShelf() {
        when(medicineMapper.reduceStock(1L, 1)).thenReturn(0);
        Medicine medicine = new Medicine();
        medicine.setStatus(0);
        when(medicineMapper.selectById(1L)).thenReturn(medicine);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> medicineService.reduceStock(1L, 1));
        assertEquals("药品不存在或已下架", ex.getMessage());
    }

    @Test
    void restoreStock_shouldIncreaseStock() {
        when(medicineMapper.restoreStock(1L, 2)).thenReturn(1);

        boolean result = medicineService.restoreStock(1L, 2);
        assertTrue(result);
        verify(medicineMapper, times(1)).restoreStock(1L, 2);
    }

    @Test
    void restoreStock_shouldReturnFalse_whenNoRowsAffected() {
        when(medicineMapper.restoreStock(1L, 2)).thenReturn(0);

        boolean result = medicineService.restoreStock(1L, 2);
        assertFalse(result);
    }
}
