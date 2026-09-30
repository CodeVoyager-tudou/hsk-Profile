package com.chronic.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.points.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 余额账户 Mapper（显式 SQL，并发防护在 SQL 层，同积分的做法）
 *
 * @author chronic
 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    /**
     * 入账（充值/退款）：余额原子累加，version 手动自增用于排查。
     * 影响行数 0 说明账户不存在（调用方须先懒建户）。
     */
    @Update("UPDATE user_account SET balance = balance + #{amount}, version = version + 1 "
            + "WHERE user_id = #{userId}")
    int creditBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /**
     * 扣款：判断余额与扣减在一条 SQL 里原子完成（行锁保证并发安全），
     * 余额不足时影响行数为 0 —— 两个并发扣款只有一个能成功，不可能扣成负数。
     */
    @Update("UPDATE user_account SET balance = balance - #{amount}, version = version + 1 "
            + "WHERE user_id = #{userId} AND balance >= #{amount}")
    int debitBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
