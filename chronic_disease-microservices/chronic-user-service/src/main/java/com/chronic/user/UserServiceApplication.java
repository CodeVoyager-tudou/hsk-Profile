package com.chronic.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 用户服务启动类
 *
 * @author chronic
 */
@SpringBootApplication(scanBasePackages = {"com.chronic.user", "com.chronic.common"})
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.chronic.user.mapper")
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}