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
@SpringBootApplication
@EnableScheduling
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.chronic.points.mapper")
public class PointsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PointsServiceApplication.class, args);
    }
}