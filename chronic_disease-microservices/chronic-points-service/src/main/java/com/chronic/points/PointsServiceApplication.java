package com.chronic.points;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 积分服务启动类
 *
 * @author chronic
 */
// scanBasePackages 必须带上 com.chronic.common：全局异常处理器（GlobalExceptionHandler）在那里，
// 否则本服务抛出的 BusinessException（积分/余额不足等）没有 @RestControllerAdvice 接住，
// 会冒泡成 HTTP 500 + Spring 默认错误体（无 code/message），调用方只能看到"服务不可用"，
// 把业务规则的拒绝误报成系统故障。user/shop 服务一直是这么扫的，本服务此前漏了。
@SpringBootApplication(scanBasePackages = {"com.chronic.points", "com.chronic.common"})
@EnableScheduling
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.chronic.points.mapper")
public class PointsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PointsServiceApplication.class, args);
    }
}