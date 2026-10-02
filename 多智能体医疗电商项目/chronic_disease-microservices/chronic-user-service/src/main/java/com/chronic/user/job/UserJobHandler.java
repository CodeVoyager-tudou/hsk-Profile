package com.chronic.user.job;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户服务 XXL-Job 定时任务处理器
 *
 * @author chronic
 */
@Slf4j
@Component
public class UserJobHandler {

    /**
     * 定时任务：同步用户状态（如禁用/启用）
     *
     * @param param XXL-Job 调度参数
     * @return 执行结果
     */
    @XxlJob("syncUserStatusJob")
    public ReturnT<String> syncUserStatus(String param) {
        log.info("开始执行用户状态同步定时任务, param={}", param);
        return ReturnT.SUCCESS;
    }
}