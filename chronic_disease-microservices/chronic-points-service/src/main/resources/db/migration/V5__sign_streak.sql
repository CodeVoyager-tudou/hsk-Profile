-- =============================================
-- 连续签到（跨周断签重置）所需字段
-- 背景：签到记录SignInRecord.consecutiveDays存的是"本周第几天"（派生值），
-- 跨周的连续签到需要一个持久化的计数器：user_points.consecutive_days。
-- 定时任务 resetConsecutiveDaysJob 每日检查：昨天未签到的用户连续天数归零（断签重置）。
-- =============================================

ALTER TABLE user_points
    ADD COLUMN consecutive_days INT NOT NULL DEFAULT 0 COMMENT '连续签到天数（昨天未签到则由定时任务归零）',
    ADD COLUMN last_sign_date DATE NULL COMMENT '最近一次签到日期（断签判定依据）';
