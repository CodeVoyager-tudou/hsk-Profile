package com.chronic.shop.pay;

import com.chronic.shop.entity.ShopOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模拟支付渠道（本项目最终采用的实现）。
 * <p>
 * 下单即确认支付，等价于"一键购买"，不产生任何真实资金流水；PAY_MOCK_AUTO_CONFIRM=false 时
 * 订单停在 PENDING，等待模拟回调 /shop/order/pay/callback 推进（用于演示/验证支付回调幂等）。
 *
 * @author chronic
 */
@Slf4j
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Value("${chronic.pay.mock-auto-confirm:true}")
    private boolean autoConfirm;

    @Override
    public boolean confirmImmediately() {
        return autoConfirm;
    }

    @Override
    public String createPayUrl(ShopOrder order) {
        log.info("[模拟支付渠道] 订单 {} 待支付，等待渠道回调确认（金额 {}）",
                order.getOrderNo(), order.getTotalAmount());
        return null;
    }
}
