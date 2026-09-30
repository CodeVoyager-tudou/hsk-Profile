package com.chronic.points.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chronic.common.exception.BusinessException;
import com.chronic.points.entity.SignInRecord;
import com.chronic.points.entity.UserPoints;
import com.chronic.points.mapper.SignInRecordMapper;
import com.chronic.points.service.SignInService;
import com.chronic.points.service.UserPointsService;
import com.chronic.points.vo.WeekSignInVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 签到规则:以自然周(周一~周日)为循环,每天的奖励只取决于星期几,
 * 断签无惩罚,周一自动开启新一轮;集满一周 7 天额外发放全勤奖励。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignInServiceImpl extends ServiceImpl<SignInRecordMapper, SignInRecord> implements SignInService {

    private final UserPointsService userPointsService;

    private static final int FULL_ATTENDANCE_BONUS = 100;
    /** 周一到周日的名称映射 */
    private static final String[] WEEK_DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 星期几对应的签到积分奖励，周一最少、周日最多 */
    private static final Map<DayOfWeek, Integer> WEEK_POINTS;

    static {
        Map<DayOfWeek, Integer> points = new EnumMap<>(DayOfWeek.class);
        points.put(DayOfWeek.MONDAY, 10);
        points.put(DayOfWeek.TUESDAY, 15);
        points.put(DayOfWeek.WEDNESDAY, 20);
        points.put(DayOfWeek.THURSDAY, 25);
        points.put(DayOfWeek.FRIDAY, 30);
        points.put(DayOfWeek.SATURDAY, 40);
        points.put(DayOfWeek.SUNDAY, 50);
        WEEK_POINTS = points;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SignInRecord signIn(Long userId) {
        LocalDate today = LocalDate.now();
        // 判断当天是否已签到
        SignInRecord exists = getOne(new LambdaQueryWrapper<SignInRecord>()
                .eq(SignInRecord::getUserId, userId)
                .eq(SignInRecord::getSignDate, today));
        if (exists != null) {
            throw new BusinessException("今日已领取，请明天再来");
        }
        // 根据星期几获取对应积分
        int points = WEEK_POINTS.get(today.getDayOfWeek());
        // consecutiveDays 在周循环规则下表示"本周已签到天数"
        int weekSignedDays = countWeekSigned(userId, today) + 1;
        SignInRecord record = new SignInRecord();
        record.setUserId(userId);
        record.setSignDate(today);
        record.setPoints(points);
        record.setConsecutiveDays(weekSignedDays);
        try {
            save(record);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 先查后插的并发窗口：双击/重试撞 uk_user_date 唯一键，按业务提示处理而非 500
            throw new BusinessException("今日已领取，请明天再来");
        }
        // 发放签到积分
        userPointsService.addPoints(userId, points, "SIGN_IN", record.getId(),
                "每周签到-" + WEEK_DAY_NAMES[today.getDayOfWeek().getValue() - 1] + "，获得" + points + "积分");
        // 维护跨周连续签到天数（user_points.consecutive_days，断签由每日任务归零）
        userPointsService.applySignInStreak(userId, today);
        // 本周第 7 天签到成功即触发全勤奖励（一周只可能发生一次）
        if (weekSignedDays == 7) {
            userPointsService.addPoints(userId, FULL_ATTENDANCE_BONUS, "SIGN_IN_BONUS", record.getId(),
                    "本周签到全勤，额外奖励" + FULL_ATTENDANCE_BONUS + "积分");
        }
        log.info("用户 {} 签到成功，本周第 {} 天，获得 {} 积分", userId, weekSignedDays, points);
        return record;
    }

    @Override
    public WeekSignInVO getWeekSignIn(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        // 查询本周已签到的日期集合
        Set<LocalDate> signedDates = list(new LambdaQueryWrapper<SignInRecord>()
                .eq(SignInRecord::getUserId, userId)
                .between(SignInRecord::getSignDate, weekStart, weekStart.plusDays(6)))
                .stream()
                .map(SignInRecord::getSignDate)
                .collect(Collectors.toSet());

        WeekSignInVO vo = new WeekSignInVO();
        vo.setWeekStart(weekStart);
        vo.setWeekEnd(weekStart.plusDays(6));
        vo.setSignedDays(signedDates.size());
        vo.setFullAttendance(signedDates.size() >= 7);
        vo.setFullAttendanceBonus(FULL_ATTENDANCE_BONUS);
        vo.setTodayPoints(WEEK_POINTS.get(today.getDayOfWeek()));
        vo.setTodaySigned(signedDates.contains(today));
        // 连续签到天数（持久化在 user_points，断签由每日定时任务归零）。
        // 只读查询、不懒建户：从未签到/未触发过积分变动的用户没有账户行，展示为 0
        UserPoints points = userPointsService.getOne(new LambdaQueryWrapper<UserPoints>().eq(UserPoints::getUserId, userId));

        // 构造本周每天的签到详情
        List<WeekSignInVO.DayInfo> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = weekStart.plusDays(i);
            WeekSignInVO.DayInfo day = new WeekSignInVO.DayInfo();
            day.setDate(date);
            day.setWeekDay(WEEK_DAY_NAMES[i]);
            day.setPoints(WEEK_POINTS.get(date.getDayOfWeek()));
            day.setSigned(signedDates.contains(date));
            days.add(day);
        }
        vo.setDays(days);
        return vo;
    }

    /**
     * 批量断签重置：昨天未签到且连续签到天数 > 0 的账户归零（定时任务入口）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int resetBrokenStreaks() {
        return userPointsService.resetBrokenStreaks(java.time.LocalDate.now());
    }

    /**
     * 统计用户本周已签到天数
     */
    private int countWeekSigned(Long userId, LocalDate today) {
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        // 一周最多 7 条，安全转换
        return Math.toIntExact(count(new LambdaQueryWrapper<SignInRecord>()
                .eq(SignInRecord::getUserId, userId)
                .between(SignInRecord::getSignDate, weekStart, weekStart.plusDays(6))));
    }
}