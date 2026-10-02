package com.chronic.shop.pay;

import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.ShopOrder;
import com.chronic.shop.feign.PointsFeignClient;
import com.chronic.shop.mapper.ShopOrderMapper;
import com.chronic.shop.mq.OrderEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 支付确认的幂等与"支付成功才发积分"语义
 */
@ExtendWith(MockitoExtension.class)
class OrderPaymentServiceTest {

    @Mock
    private ShopOrderMapper shopOrderMapper;

    @Mock
    private PointsFeignClient pointsFeignClient;

    private OrderPaymentService orderPaymentService;

    @BeforeEach
    void setUp() {
        orderPaymentService = new OrderPaymentService(shopOrderMapper, pointsFeignClient,
                new OrderEventPublisher(Optional.empty()));
    }

    private ShopOrder order(String status, int pointsEarned) {
        ShopOrder order = new ShopOrder();
        order.setId(1L);
        order.setOrderNo("ORDER-1");
        order.setUserId(1L);
        order.setMedicineName("苯磺酸氨氯地平片");
        order.setStatus(status);
        order.setPayType("CASH");
        order.setTotalAmount(BigDecimal.valueOf(57));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPointsEarned(pointsEarned);
        order.setPointsStatus(0);
        return order;
    }

    @Test
    void confirmPaid_shouldPromotePendingOrderAndGrantPoints() {
        when(shopOrderMapper.selectById(1L)).thenReturn(order("PENDING", 56));
        when(shopOrderMapper.confirmPaid(1L)).thenReturn(1);
        when(pointsFeignClient.addPoints(eq(1L), eq(56), eq("ORDER_PURCHASE"), eq(1L), anyString()))
                .thenReturn(Result.success(true));

        ShopOrder paid = orderPaymentService.confirmPaid(1L);

        assertEquals("PAID", paid.getStatus());
        assertNotNull(paid.getPayTime());
        assertEquals(1, paid.getPointsStatus());
        verify(shopOrderMapper, times(1)).confirmPaid(1L);
        verify(shopOrderMapper, times(1)).markPointsGranted(1L);
        verify(pointsFeignClient, times(1)).addPoints(eq(1L), eq(56), eq("ORDER_PURCHASE"), eq(1L), anyString());
    }

    @Test
    void confirmPaid_shouldBeIdempotent_whenAlreadyPaid() {
        when(shopOrderMapper.selectById(1L)).thenReturn(order("PAID", 56));

        ShopOrder paid = orderPaymentService.confirmPaid(1L);

        assertEquals("PAID", paid.getStatus());
        // 重复回调不得再发一次积分，也不该再推进状态
        verify(shopOrderMapper, never()).confirmPaid(anyLong());
        verify(pointsFeignClient, never()).addPoints(anyLong(), anyInt(), anyString(), any(), anyString());
    }

    @Test
    void confirmPaid_shouldReject_whenOrderCancelled() {
        when(shopOrderMapper.selectById(1L)).thenReturn(order("CANCELLED", 56));

        BusinessException ex = assertThrows(BusinessException.class, () -> orderPaymentService.confirmPaid(1L));
        assertTrue(ex.getMessage().contains("不可支付"));
        verify(pointsFeignClient, never()).addPoints(anyLong(), anyInt(), anyString(), any(), anyString());
    }

    @Test
    void confirmPaid_shouldKeepPointsPending_whenPointsServiceDown() {
        when(shopOrderMapper.selectById(1L)).thenReturn(order("PENDING", 56));
        when(shopOrderMapper.confirmPaid(1L)).thenReturn(1);
        when(pointsFeignClient.addPoints(anyLong(), anyInt(), anyString(), any(), anyString()))
                .thenReturn(Result.error(503, "积分服务暂时不可用"));

        ShopOrder paid = orderPaymentService.confirmPaid(1L);

        // 支付成功不回滚，积分保持待补偿（points_status=0），由补偿任务补发
        assertEquals("PAID", paid.getStatus());
        assertEquals(0, paid.getPointsStatus());
        verify(shopOrderMapper, never()).markPointsGranted(any());
    }

    @Test
    void confirmPaid_shouldThrow_whenOrderMissing() {
        when(shopOrderMapper.selectById(1L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> orderPaymentService.confirmPaid(1L));
    }
}
