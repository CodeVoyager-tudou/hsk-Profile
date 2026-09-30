package com.chronic.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chronic.common.exception.BusinessException;
import com.chronic.points.entity.PointsRecord;
import com.chronic.points.entity.UserPoints;
import com.chronic.points.mapper.PointsRecordMapper;
import com.chronic.points.mapper.UserPointsMapper;
import com.chronic.points.service.UserPointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 积分服务实现，提供积分增加、扣减、退还、流水记录等核心功能
 * <p>
 * 使用 @Version 乐观锁防止并发积分操作导致的数据不一致。
 * </p>
 *
 * @author chronic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPointsServiceImpl extends ServiceImpl<UserPointsMapper, UserPoints> implements UserPointsService {

    private final PointsRecordMapper pointsRecordMapper;

    @Override
    public UserPoints getByUserId(Long userId) {
        // 查询用户积分账户，不存在则新建（totalPoints=0, usedPoints=0）。
        // user_id 唯一键保证至多一行，getOne 单参版走 selectOne
        UserPoints userPoints = getOne(new LambdaQueryWrapper<UserPoints>().eq(UserPoints::getUserId, userId));
        if (userPoints == null) {
            userPoints = new UserPoints();
            userPoints.setUserId(userId);
            userPoints.setTotalPoints(0);
            userPoints.setUsedPoints(0);
            try {
                save(userPoints);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首次查询：另一个请求已建户，重查返回已存在账户
                userPoints = getOne(new LambdaQueryWrapper<UserPoints>().eq(UserPoints::getUserId, userId));
            }
        }
        return userPoints;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addPoints(Long userId, Integer points, String type, Long sourceId, String remark) {
        checkPoints(points);
        getByUserId(userId);
        // 先插流水作幂等护栏：重复请求（补偿任务与主流程并发、Feign 重试）撞唯一键时
        // 直接按"已处理"成功返回，余额不会重复累加；后续余额更新失败则整笔事务回滚（含流水）
        if (!saveRecord(userId, points, type, sourceId, remark)) {
            log.info("积分流水已存在（幂等命中），跳过重复加分: user={}, type={}, sourceId={}",
                    userId, type, sourceId);
            return true;
        }
        // 原子累加 totalPoints，防并发丢更新
        if (baseMapper.increaseTotalPoints(userId, points) == 0) {
            throw new BusinessException("积分更新失败，请重试");
        }
        log.info("用户 {} 积分变动: +{} ({})", userId, points, type);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductPoints(Long userId, Integer points, String type, Long sourceId, String remark) {
        checkPoints(points);
        getByUserId(userId);
        if (!saveRecord(userId, -points, type, sourceId, remark)) {
            log.info("积分流水已存在（幂等命中），跳过重复扣分: user={}, type={}, sourceId={}",
                    userId, type, sourceId);
            return true;
        }
        // 原子扣减：累加 usedPoints，由 SQL 层校验可用余额（totalPoints - usedPoints >= points），防并发超扣
        int rows = baseMapper.increaseUsedPoints(userId, points);
        if (rows == 0) {
            throw new BusinessException("积分余额不足");
        }
        log.info("用户 {} 积分扣减: -{} ({}), 剩余可用: total-used", userId, points, type);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean refundPoints(Long userId, Integer points, String type, Long sourceId, String remark) {
        checkPoints(points);
        if (!saveRecord(userId, points, type, sourceId, remark)) {
            log.info("积分流水已存在（幂等命中），跳过重复退分: user={}, type={}, sourceId={}",
                    userId, type, sourceId);
            return true;
        }
        // 原子退还：减少 usedPoints
        int rows = baseMapper.decreaseUsedPoints(userId, points);
        if (rows == 0) {
            throw new BusinessException("积分退还失败，已用积分不足");
        }
        log.info("用户 {} 积分退还: +{} ({})", userId, points, type);
        return true;
    }

    private void checkPoints(Integer points) {
        if (points == null || points <= 0) {
            throw new BusinessException("积分数量不合法");
        }
    }

    /**
     * 签到后续连连续天数：单条 SQL 完成"昨天签没签的判定 + 连续数更新"（见 Mapper 注释）。
     * 账户不存在（影响 0 行）时懒建户后重试一次——与 addPoints 的懒建户口径一致。
     * 连续天数跨自然周连续（周日签到、周一签到不断），只在隔天未签时断签归 1。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int applySignInStreak(Long userId, java.time.LocalDate today) {
        java.time.LocalDate yesterday = today.minusDays(1);
        int rows = baseMapper.applySignInStreak(userId, today, yesterday);
        if (rows == 0) {
            getByUserId(userId);
            rows = baseMapper.applySignInStreak(userId, today, yesterday);
        }
        UserPoints after = getOne(new LambdaQueryWrapper<UserPoints>().eq(UserPoints::getUserId, userId));
        int consecutive = after == null || after.getConsecutiveDays() == null ? 1 : after.getConsecutiveDays();
        log.info("用户 {} 连续签到天数更新为 {}", userId, consecutive);
        return consecutive;
    }

    /**
     * 批量断签重置：昨天（含更早）未签到且连续天数 > 0 的账户归零。
     * 一条批量 UPDATE 原子完成，幂等——重复执行第二个条件不再满足。
     * 兜底说明：签到时已按 last_sign_date 实时维护连续数，本方法处理的是
     * "签完就再没回来"的用户——他们的持久化连续数会一直挂着旧值，由每日任务归零。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int resetBrokenStreaks(java.time.LocalDate today) {
        int reset = baseMapper.resetBrokenStreaks(today.minusDays(1));
        if (reset > 0) {
            log.warn("断签重置完成：{} 个账户的连续签到天数归零（截至 {} 未签到）", reset, today.minusDays(1));
        }
        return reset;
    }

    /**
     * 记录积分流水（正数为增加，负数为扣减）。
     * (type, source_id) 唯一键是幂等护栏：撞键返回 false，由调用方按"已处理"跳过。
     * 调用方处于事务内，后续步骤失败时本条流水随事务一起回滚，不会留下"有流水无变动"的脏数据。
     */
    private boolean saveRecord(Long userId, Integer points, String type, Long sourceId, String remark) {
        PointsRecord record = new PointsRecord();
        record.setUserId(userId);
        record.setPoints(points);
        record.setType(type);
        record.setSourceId(sourceId);
        record.setRemark(remark);
        try {
            pointsRecordMapper.insert(record);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }
}