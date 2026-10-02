package com.chronic.points.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chronic.common.exception.BusinessException;
import com.chronic.points.entity.AccountRecord;
import com.chronic.points.entity.UserAccount;
import com.chronic.points.mapper.AccountRecordMapper;
import com.chronic.points.mapper.UserAccountMapper;
import com.chronic.points.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 余额账户服务实现 —— 与积分服务同一套骨架：
 * <ol>
 *   <li>懒建户：首次操作自动建账户（user_id 唯一键 + DuplicateKeyException 重查，防并发建重）；</li>
 *   <li>先插流水作幂等护栏，撞 (type, source_id) 唯一键按"已处理"返回 true；</li>
 *   <li>余额变动走 SQL 原子条件更新，并发超扣在数据库层面就不可能发生。</li>
 * </ol>
 *
 * @author chronic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAccountServiceImpl extends ServiceImpl<UserAccountMapper, UserAccount> implements UserAccountService {

    /** 模拟渠道的单笔充值上限；真实渠道该限制来自支付方式本身的规则 */
    private static final BigDecimal MAX_RECHARGE_PER_TX = new BigDecimal("5000");

    /** 充值限频：同一用户 1 分钟内最多充值次数（防脚本刷充值接口） */
    private static final int RECHARGE_LIMIT_PER_MIN = 3;

    private final AccountRecordMapper accountRecordMapper;

    @Override
    public UserAccount getByUserId(Long userId) {
        UserAccount account = getOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUserId, userId));
        if (account == null) {
            account = new UserAccount();
            account.setUserId(userId);
            account.setBalance(BigDecimal.ZERO);
            try {
                save(account);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首次建户：另一请求已建，重查返回已存在账户
                account = getOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUserId, userId));
            }
        }
        return account;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal recharge(Long userId, BigDecimal amount, String remark) {
        checkAmount(amount);
        if (amount.compareTo(MAX_RECHARGE_PER_TX) > 0) {
            throw new BusinessException("单笔充值不能超过 " + MAX_RECHARGE_PER_TX.stripTrailingZeros().toPlainString() + " 元");
        }
        // 充值限频：1 分钟内超过 3 次直接拒绝（用流水表计数即可，无需引入 Redis）
        if (accountRecordMapper.countRecentRecharges(userId) >= RECHARGE_LIMIT_PER_MIN) {
            throw new BusinessException(429, "充值操作过于频繁，请稍后再试");
        }
        getByUserId(userId);
        // 充值没有业务单据可作幂等来源，source_id 用雪花号：每次充值一条流水
        Long rechargeNo = IdUtil.getSnowflakeNextId();
        if (!saveRecord(userId, amount, AccountRecord.TYPE_RECHARGE, rechargeNo, remark)) {
            // 理论不可达（雪花号唯一）；防御性处理，避免极端重试场景重复入账
            throw new BusinessException("充值流水已存在，请勿重复提交");
        }
        if (baseMapper.creditBalance(userId, amount) == 0) {
            throw new BusinessException("账户更新失败，请重试");
        }
        log.info("用户 {} 充值: +{} 元, 充值流水号 {}", userId, amount, rechargeNo);
        return getByUserId(userId).getBalance();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductBalance(Long userId, BigDecimal amount, String type, Long sourceId, String remark) {
        checkAmount(amount);
        getByUserId(userId);
        // 先插流水：撞唯一键说明这笔扣款已处理（Feign 重试/补偿任务重跑），按成功返回
        if (!saveRecord(userId, amount.negate(), type, sourceId, remark)) {
            log.info("余额流水已存在（幂等命中），跳过重复扣款: user={}, type={}, sourceId={}",
                    userId, type, sourceId);
            return true;
        }
        // SQL 层原子扣减：balance >= amount 才生效，0 行 = 余额不足（抛异常回滚流水，不落脏数据）
        if (baseMapper.debitBalance(userId, amount) == 0) {
            throw new BusinessException(400, "余额不足");
        }
        log.info("用户 {} 余额扣款: -{} 元 ({}, sourceId={})", userId, amount, type, sourceId);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean refundBalance(Long userId, BigDecimal amount, String type, Long sourceId, String remark) {
        checkAmount(amount);
        getByUserId(userId);
        if (!saveRecord(userId, amount, type, sourceId, remark)) {
            log.info("余额流水已存在（幂等命中），跳过重复退款: user={}, type={}, sourceId={}",
                    userId, type, sourceId);
            return true;
        }
        if (baseMapper.creditBalance(userId, amount) == 0) {
            throw new BusinessException("账户更新失败，请重试");
        }
        log.info("用户 {} 余额退款: +{} 元 ({}, sourceId={})", userId, amount, type, sourceId);
        return true;
    }

    @Override
    public List<AccountRecord> getRecords(Long userId, int limit) {
        return accountRecordMapper.selectList(new LambdaQueryWrapper<AccountRecord>()
                .eq(AccountRecord::getUserId, userId)
                .orderByDesc(AccountRecord::getCreateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    private void checkAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额不合法");
        }
    }

    /**
     * 记录资金流水（正数入账，负数扣款）。
     * (type, source_id) 唯一键是幂等护栏：撞键返回 false；调用方在事务内，
     * 后续步骤失败时本条流水随事务回滚，不会留下"有流水无变动"的脏数据。
     */
    private boolean saveRecord(Long userId, BigDecimal amount, String type, Long sourceId, String remark) {
        AccountRecord record = new AccountRecord();
        record.setUserId(userId);
        record.setAmount(amount);
        record.setType(type);
        record.setSourceId(sourceId == null ? 0L : sourceId);
        record.setRemark(remark);
        try {
            accountRecordMapper.insert(record);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }
}
