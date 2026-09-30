package com.chronic.points.job;

import com.chronic.points.service.SignInService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 积分服务定时任务处理器。
 *
 * <h3>连续签到断签重置（resetConsecutiveDaysJob）</h3>
 * 用户积分表 user_points 上持久化了连续签到天数（consecutive_days + last_sign_date，
 * 签到时实时维护：昨天签过则 +1，断签则重置为 1）。本任务处理的是<b>签完就再没回来</b>
 * 的用户——他们的连续天数会一直挂着旧值，由本任务在每日凌晨批量归零（断签重置）。
 *
 * <p>触发路径有两条，关单/重置 SQL 都是幂等的（条件不满足影响 0 行），双跑无害：</p>
 * <ul>
 *   <li>xxl-job 调度中心（生产）：{@code resetConsecutiveDaysJob}；</li>
 *   <li>本地 @Scheduled 兜底（默认每日 00:10）：开发/演示环境没有调度中心，
 *       与订单超时兜底任务（OrderTimeoutSweepJob）同一模式，保证兜底永远在线。</li>
 * </ul>
 *
 * @author chronic
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsJobHandler {

    private final SignInService signInService;

    /** 本地兜底调度开关：生产已有 xxl-job 时可置 false 关闭（双跑也无害，SQL 幂等） */
    @Value("${chronic.points.streak-reset-scheduled:true}")
    private boolean scheduledEnabled;

    /**
     * 跨天锁：防止 fixedDelay 与 xxl-job 触发在同一时刻并发执行。
     * 底层 SQL 本身幂等，这里只是避免日志与版本号无意义地翻倍。
     */
    private final ReentrantLock resetLock = new ReentrantLock();

    /**
     * 定时任务：检查并重置连续签到天数
     * 用于处理跨周签到断签等场景（昨天未签到且连续天数 > 0 的账户归零）
     *
     * @return 本次归零的账户数
     */
    @XxlJob("resetConsecutiveDaysJob")
    public int resetConsecutiveDaysJob() {
        resetLock.lock();
        try {
            log.info("开始检查连续签到（截止昨天未签到的账户将归零）...");
            int reset = signInService.resetBrokenStreaks();
            log.info("连续签到检查完成，本次重置 {} 个账户", reset);
            return reset;
        } catch (Exception e) {
            log.error("连续签到检查失败", e);
            throw e;
        } finally {
            resetLock.unlock();
        }
    }

    /** 本地兜底：每日 00:10 执行（用户"昨天"的判定在凌晨时分最准确） */
    @Scheduled(cron = "${chronic.points.streak-reset-cron:0 10 0 * * ?}")
    public void scheduledStreakReset() {
        if (scheduledEnabled) {
            resetConsecutiveDaysJob();
        }
    }
}
