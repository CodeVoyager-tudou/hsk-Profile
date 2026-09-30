package com.chronic.points.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 本周签到状态(周一~周日为一个签到周期)
 */
@Data
public class WeekSignInVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private LocalDate weekStart;

    private LocalDate weekEnd;

    /** 本周已签到天数 */
    private Integer signedDays;

    /** 本周是否已达成全勤(7天) */
    private Boolean fullAttendance;

    /** 全勤额外奖励积分 */
    private Integer fullAttendanceBonus;

    /** 今日签到可领积分 */
    private Integer todayPoints;

    /** 今日是否已签到 */
    private Boolean todaySigned;

    /** 连续签到天数（跨周连续；昨天未签到则由定时任务归零） */
    private Integer consecutiveDays;

    private List<DayInfo> days;

    @Data
    public static class DayInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        private LocalDate date;

        /** 周一~周日 */
        private String weekDay;

        /** 当天签到可领积分 */
        private Integer points;

        private Boolean signed;
    }
}
