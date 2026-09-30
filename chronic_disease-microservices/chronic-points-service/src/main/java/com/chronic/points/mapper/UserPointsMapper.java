package com.chronic.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.points.entity.UserPoints;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface UserPointsMapper extends BaseMapper<UserPoints> {

    /**
     * 原子增加累计积分(防并发丢失更新)
     */
    @Update("UPDATE user_points SET total_points = total_points + #{points}, version = version + 1 "
            + "WHERE user_id = #{userId}")
    int increaseTotalPoints(@Param("userId") Long userId, @Param("points") Integer points);

    /**
     * 原子扣减:可用积分 = total_points - used_points,余额不足时影响行数为 0
     */
    @Update("UPDATE user_points SET used_points = used_points + #{points}, version = version + 1 "
            + "WHERE user_id = #{userId} AND total_points - used_points >= #{points}")
    int increaseUsedPoints(@Param("userId") Long userId, @Param("points") Integer points);

    /**
     * 原子退还:仅在有足够已用积分时生效
     */
    @Update("UPDATE user_points SET used_points = used_points - #{points}, version = version + 1 "
            + "WHERE user_id = #{userId} AND used_points >= #{points}")
    int decreaseUsedPoints(@Param("userId") Long userId, @Param("points") Integer points);

    /**
     * 签到后续连：昨天签过 → 连续天数 +1；断签（含隔天未签/首次签到）→ 重置为 1。
     * 单条 SQL 完成"判定 + 更新"，无先查后改的并发窗口；影响 0 行 = 用户还没有积分账户，
     * 由调用方懒建户后重试（见 UserPointsServiceImpl.applySignInStreak）。
     */
    @Update("UPDATE user_points SET consecutive_days = CASE WHEN last_sign_date = #{yesterday} "
            + "THEN consecutive_days + 1 ELSE 1 END, last_sign_date = #{today}, version = version + 1 "
            + "WHERE user_id = #{userId}")
    int applySignInStreak(@Param("userId") Long userId, @Param("today") LocalDate today,
                          @Param("yesterday") LocalDate yesterday);

    /**
     * 批量断签重置：昨天未签到且连续天数大于 0 的账户归零。
     * 条件即幂等键——重复执行第二个条件不再满足，影响 0 行；定时任务与手动触发可并存。
     *
     * @return 本次归零的账户数
     */
    @Update("UPDATE user_points SET consecutive_days = 0, version = version + 1 "
            + "WHERE consecutive_days > 0 AND (last_sign_date IS NULL OR last_sign_date < #{yesterday})")
    int resetBrokenStreaks(@Param("yesterday") LocalDate yesterday);
}
