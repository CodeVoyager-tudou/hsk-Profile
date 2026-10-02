package com.chronic.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chronic.common.exception.BusinessException;
import com.chronic.points.entity.AccountRecord;
import com.chronic.points.entity.UserAccount;
import com.chronic.points.mapper.AccountRecordMapper;
import com.chronic.points.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceImplTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    @Mock
    private UserAccountMapper userAccountMapper;

    @Mock
    private AccountRecordMapper accountRecordMapper;

    private UserAccountServiceImpl accountService;

    @BeforeEach
    void setUp() {
        accountService = new UserAccountServiceImpl(accountRecordMapper);
        ReflectionTestUtils.setField(accountService, "baseMapper", userAccountMapper);
    }

    private UserAccount account(long userId, String balance) {
        UserAccount account = new UserAccount();
        account.setUserId(userId);
        account.setBalance(new BigDecimal(balance));
        return account;
    }

    @Test
    void getByUserId_shouldLazyCreate_whenAbsent() {
        // 第一次查无账户 → 建户撞唯一键（并发建户）→ 重查返回已存在账户
        UserAccount existing = account(9L, "0.00");
        when(userAccountMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(null, existing);
        when(userAccountMapper.insert(any(UserAccount.class)))
                .thenThrow(new DuplicateKeyException("uk_user_account_user"));

        UserAccount result = accountService.getByUserId(9L);
        assertEquals(9L, result.getUserId());
        verify(userAccountMapper, times(2)).selectOne(any(LambdaQueryWrapper.class), anyBoolean());
    }

    @Test
    void recharge_shouldInsertRecordAndCredit() {
        UserAccount account = account(1L, HUNDRED.toPlainString());
        when(userAccountMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(account);
        when(accountRecordMapper.insert(any(AccountRecord.class))).thenReturn(1);
        when(userAccountMapper.creditBalance(eq(1L), any(BigDecimal.class))).thenReturn(1);

        accountService.recharge(1L, new BigDecimal("50.00"), "模拟充值");

        ArgumentCaptor<AccountRecord> captor = ArgumentCaptor.forClass(AccountRecord.class);
        verify(accountRecordMapper).insert(captor.capture());
        assertEquals(AccountRecord.TYPE_RECHARGE, captor.getValue().getType());
        assertEquals(0, new BigDecimal("50.00").compareTo(captor.getValue().getAmount()));
        verify(userAccountMapper).creditBalance(eq(1L), eq(new BigDecimal("50.00")));
    }

    @Test
    void recharge_shouldReject_whenRateLimited() {
        // 充值限频：1 分钟内已有 3 次充值 → 第 4 次直接 429，不触达流水与余额
        when(accountRecordMapper.countRecentRecharges(1L)).thenReturn(3);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountService.recharge(1L, new BigDecimal("50.00"), "模拟充值"));
        assertTrue(ex.getMessage().contains("过于频繁"));
        verify(accountRecordMapper, never()).insert(any(AccountRecord.class));
    }

    @Test
    void recharge_shouldRejectInvalidOrOverLimitAmount() {
        assertThrows(BusinessException.class, () -> accountService.recharge(1L, BigDecimal.ZERO, null));
        assertThrows(BusinessException.class, () -> accountService.recharge(1L, new BigDecimal("-1"), null));
        assertThrows(BusinessException.class, () -> accountService.recharge(1L, new BigDecimal("5000.01"), null));
        verify(userAccountMapper, never()).creditBalance(anyLong(), any(BigDecimal.class));
    }

    @Test
    void deductBalance_shouldDebit_whenRecordInserted() {
        when(userAccountMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(account(1L, "88.00"));
        when(accountRecordMapper.insert(any(AccountRecord.class))).thenReturn(1);
        when(userAccountMapper.debitBalance(1L, new BigDecimal("19.90"))).thenReturn(1);

        assertTrue(accountService.deductBalance(1L, new BigDecimal("19.90"),
                AccountRecord.TYPE_BALANCE_PAY, 101L, "订单支付"));
        verify(userAccountMapper).debitBalance(1L, new BigDecimal("19.90"));
    }

    @Test
    void deductBalance_shouldReturnTrue_whenIdempotentHit() {
        // 幂等命中：流水唯一键 (type, source_id) 撞键 → 按"已处理"成功返回，不再扣款
        when(accountRecordMapper.insert(any(AccountRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_type_source"));

        assertTrue(accountService.deductBalance(1L, new BigDecimal("19.90"),
                AccountRecord.TYPE_BALANCE_PAY, 101L, "订单支付"));
        verify(userAccountMapper, never()).debitBalance(anyLong(), any(BigDecimal.class));
    }

    @Test
    void deductBalance_shouldThrow_whenInsufficient() {
        // 余额不足：扣款 SQL 影响 0 行 → 抛业务异常；流水随事务回滚（不落"有流水无变动"脏数据）
        when(userAccountMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(account(1L, "5.00"));
        when(accountRecordMapper.insert(any(AccountRecord.class))).thenReturn(1);
        when(userAccountMapper.debitBalance(1L, new BigDecimal("19.90"))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                accountService.deductBalance(1L, new BigDecimal("19.90"),
                        AccountRecord.TYPE_BALANCE_PAY, 101L, "订单支付"));
        assertTrue(ex.getMessage().contains("余额不足"));
    }

    @Test
    void refundBalance_shouldCredit_whenRecordInserted() {
        when(userAccountMapper.selectOne(any(LambdaQueryWrapper.class), anyBoolean())).thenReturn(account(1L, "0.00"));
        when(accountRecordMapper.insert(any(AccountRecord.class))).thenReturn(1);
        when(userAccountMapper.creditBalance(1L, new BigDecimal("19.90"))).thenReturn(1);

        assertTrue(accountService.refundBalance(1L, new BigDecimal("19.90"),
                AccountRecord.TYPE_BALANCE_REFUND, 101L, "订单退款"));
        verify(userAccountMapper).creditBalance(1L, new BigDecimal("19.90"));
    }

    @Test
    void refundBalance_shouldReturnTrue_whenIdempotentHit() {
        when(accountRecordMapper.insert(any(AccountRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_type_source"));

        assertTrue(accountService.refundBalance(1L, new BigDecimal("19.90"),
                AccountRecord.TYPE_BALANCE_REFUND, 101L, "订单退款"));
        verify(userAccountMapper, never()).creditBalance(anyLong(), any(BigDecimal.class));
    }
}
