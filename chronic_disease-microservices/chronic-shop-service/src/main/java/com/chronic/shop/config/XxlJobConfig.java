package com.chronic.shop.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-Job 分布式定时任务配置
 *
 * @author chronic
 */
@Slf4j
@Configuration
public class XxlJobConfig {

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${xxl.job.executor.appname}")
    private String appname;

    @Value("${xxl.job.executor.port}")
    private int port;

    @Value("${xxl.job.accessToken}")
    private String accessToken;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        log.info(">>>>>>>>>>> xxl-job config init.");
        // 初始化 XXL-Job Spring Executor
        XxlJobSpringExecutor xxlJobSpringExecutor = new XxlJobSpringExecutor();
        // 设置 XXL-Job 服务器地址
        xxlJobSpringExecutor.setAdminAddresses(adminAddresses);
        // 设置 XXL-Job 任务应用名称
        xxlJobSpringExecutor.setAppname(appname);
        // 设置 XXL-Job 任务端口
        xxlJobSpringExecutor.setPort(port);
        // 设置 XXL-Job 任务访问令牌
        xxlJobSpringExecutor.setAccessToken(accessToken);
        // 设置 XXL-Job 任务日志路径
        xxlJobSpringExecutor.setLogPath("/data/applogs/xxl-job/jobhandler");
        // 设置 XXL-Job 任务日志保留天数
        xxlJobSpringExecutor.setLogRetentionDays(30);
        return xxlJobSpringExecutor;
    }
}