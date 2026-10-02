package com.chronic.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chronic.common.exception.BusinessException;
import com.chronic.points.entity.PointsRecord;
import com.chronic.points.entity.UserPoints;
import com.chronic.points.mapper.PointsRecordMapper;
import com.chronic.points.mapper.UserPointsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPointsServiceImplTest {

    @Mock
    private UserPointsMapper userPointsMapper;

    @Mock
    private PointsRecordMapper pointsRecordMapper;

    private UserPointsServiceImpl userPointsService;

    @BeforeEach
    void setUp() {
        userPointsService = new UserPointsServiceImpl(pointsRecordMapper);
        ReflectionTestUtils.setField(userPointsService, "baseMapper", userPointsMapper);
    }

    @Test
    void getByUserId_shouldReturnExistingPoints() {
        UserPoints existing = new UserPoints();
        existing.setUserId(1L);
        existing.setTotalPoints(100);
        existing.setUsedPoints(30);

        when(userPointsMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(existing);

        UserPoints result = userPointsService.getByUserId(1L);
        assertEquals(100, result.getTotalPoints());
        assertEquals(30, result.getUsedPoints());
    }

    @Test
    void getByUserId_shouldCreateNew_whenNotExist() {
        when(userPointsMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(null);
        when(userPointsMapper.insert(any(UserPoints.class))).thenReturn(1);

        UserPoints result = userPointsService.getByUserId(99L);
        assertNotNull(result);
        assertEquals(99L, result.getUserId());
        assertEquals(0, result.getTotalPoints());
        assertEquals(0, result.getUsedPoints());
    }

    @Test
    void addPoints_shouldIncreaseTotalPoints() {
        when(userPointsMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(new UserPoints());
        when(userPointsMapper.increaseTotalPoints(1L, 50)).thenReturn(1);
        when(pointsRecordMapper.insert(any(PointsRecord.class))).thenReturn(1);

        boolean result = userPointsService.addPoints(1L, 50, "SIGN_IN", 1L, "签到奖励");
        assertTrue(result);
        verify(userPointsMapper, times(1)).increaseTotalPoints(1L, 50);
    }

    @Test
    void addPoints_shouldThrow_whenZeroPoints() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userPointsService.addPoints(1L, 0, "TEST", 1L, "test"));
        assertEquals("积分数量不合法", ex.getMessage());
    }

    @Test
    void addPoints_shouldThrow_whenNegativePoints() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userPointsService.addPoints(1L, -10, "TEST", 1L, "test"));
        assertEquals("积分数量不合法", ex.getMessage());
    }

    @Test
    void deductPoints_shouldDecreaseUsedPoints() {
        when(userPointsMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(new UserPoints());
        when(userPointsMapper.increaseUsedPoints(1L, 30)).thenReturn(1);
        when(pointsRecordMapper.insert(any(PointsRecord.class))).thenReturn(1);

        boolean result = userPointsService.deductPoints(1L, 30, "PURCHASE", 1L, "购买扣减");
        assertTrue(result);
        verify(userPointsMapper, times(1)).increaseUsedPoints(1L, 30);
    }

    @Test
    void deductPoints_shouldThrow_whenInsufficientBalance() {
        when(userPointsMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(new UserPoints());
        when(userPointsMapper.increaseUsedPoints(1L, 999)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userPointsService.deductPoints(1L, 999, "PURCHASE", 1L, "test"));
        assertEquals("积分余额不足", ex.getMessage());
    }

    @Test
    void refundPoints_shouldDecreaseUsedPoints() {
        when(userPointsMapper.decreaseUsedPoints(1L, 20)).thenReturn(1);
        when(pointsRecordMapper.insert(any(PointsRecord.class))).thenReturn(1);

        boolean result = userPointsService.refundPoints(1L, 20, "REFUND", 1L, "订单退款");
        assertTrue(result);
        verify(userPointsMapper, times(1)).decreaseUsedPoints(1L, 20);
    }

    @Test
    void refundPoints_shouldThrow_whenUsedPointsInsufficient() {
        when(userPointsMapper.decreaseUsedPoints(1L, 100)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userPointsService.refundPoints(1L, 100, "REFUND", 1L, "test"));
        assertEquals("积分退还失败，已用积分不足", ex.getMessage());
    }
}
