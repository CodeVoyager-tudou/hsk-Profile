package com.chronic.shop.pay;

import com.chronic.shop.entity.ShopOrder;

/**
 * 支付渠道抽象。
 * <p>
 * 本项目的定位是个人练习项目，<b>不接真实支付渠道</b>：{@link MockPaymentGateway} 就是最终实现
 * （相当于"模拟收银台"，下单即确认）。保留这层接口是为了：
 * <ul>
 *   <li>订单状态机 PENDING -> PAID 与渠道回调的幂等语义能真实跑通、可测试；</li>
 *   <li>万一以后要接真实渠道，只需新增一个实现类 + 回调验签，业务代码不用改。</li>
 * </ul>
 *
 * @author chronic
 */
public interface PaymentGateway {

    /**
     * 是否在下单后立即确认支付（模拟渠道/联调用；生产必须为 false）
     */
    boolean confirmImmediately();

    /**
     * 生成收银台地址；模拟渠道返回 null
     */
    String createPayUrl(ShopOrder order);
}
