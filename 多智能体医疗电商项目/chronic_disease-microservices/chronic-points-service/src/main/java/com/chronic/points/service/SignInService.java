package com.chronic.points.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chronic.points.entity.SignInRecord;
import com.chronic.points.vo.WeekSignInVO;

public interface SignInService extends IService<SignInRecord> {

    /**
     * 每日签到领积分(以自然周为循环,断签无惩罚)
     */
    SignInRecord signIn(Long userId);

    /**
     * 查询本周签到日历与全勤进度
     */
    WeekSignInVO getWeekSignIn(Long userId);

    /**
     * 批量断签重置：昨天未签到且连续签到天数 > 0 的账户归零。
     * 定时任务（xxl-job / 本地 @Scheduled 兜底）调用；幂等，重复执行影响 0 行。
     *
     * @return 本次归零的账户数
     */
    int resetBrokenStreaks();
}
