package com.chronic.points.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chronic.points.entity.UserPoints;

public interface UserPointsService extends IService<UserPoints> {

    UserPoints getByUserId(Long userId);

    /**
     * 增加积分(流水记正数)
     */
    boolean addPoints(Long userId, Integer points, String type, Long sourceId, String remark);

    /**
     * 扣减积分(余额不足抛异常,流水记负数)
     */
    boolean deductPoints(Long userId, Integer points, String type, Long sourceId, String remark);

    /**
     * 退还积分(用于兑换取消,流水记正数)
     */
    boolean refundPoints(Long userId, Integer points, String type, Long sourceId, String remark);

    /**
     * 签到后续连连续天数：昨天签过 → +1，断签/首次 → 1。
     * 账户不存在时懒建户（与 addPoints 同口径）。
     *
     * @return 更新后的连续签到天数
     */
    int applySignInStreak(Long userId, java.time.LocalDate today);

    /**
     * 批量重置断签账户的连续天数（昨天未签到且连续天数 > 0 → 归零）。
     * 定时任务（xxl-job / 本地 @Scheduled）调用；条件即幂等键，重复执行影响 0 行。
     *
     * @return 本次归零的账户数
     */
    int resetBrokenStreaks(java.time.LocalDate today);
}
