package com.chronic.points.service;

import com.chronic.points.entity.AccountRecord;
import com.chronic.points.entity.UserAccount;

import java.math.BigDecimal;
import java.util.List;

/**
 * 余额账户服务：充值（模拟渠道）、扣款（余额支付）、退款、查询。
 * <p>幂等与并发防护与积分同构：流水 (type, source_id) 唯一键 + SQL 原子条件更新。</p>
 *
 * @author chronic
 */
public interface UserAccountService {

    /** 查询账户（不存在则懒建户，余额 0） */
    UserAccount getByUserId(Long userId);

    /**
     * 充值（模拟支付渠道回调入账）。
     *
     * @return 本次入账后的余额
     */
    BigDecimal recharge(Long userId, BigDecimal amount, String remark);

    /**
     * 扣款（余额支付）。流水 (type, source_id) 幂等：重复请求按已处理返回 true；
     * 余额不足抛业务异常（事务回滚，流水不落库）。
     */
    boolean deductBalance(Long userId, BigDecimal amount, String type, Long sourceId, String remark);

    /** 退款（订单取消/补偿回款）。幂等同上。 */
    boolean refundBalance(Long userId, BigDecimal amount, String type, Long sourceId, String remark);

    /** 最近流水（最多 limit 条，按时间倒序） */
    List<AccountRecord> getRecords(Long userId, int limit);
}
