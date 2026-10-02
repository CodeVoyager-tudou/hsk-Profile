package com.chronic.shop.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端：为优惠券领取等需要跨实例串行化的场景提供分布式锁(RLock)
 * <p>
 * 设计说明：
 * <ul>
 *   <li>Redisson 的加锁、解锁、锁续期都在 Redis 侧由 Lua 脚本原子完成，业务代码不需要自己拼 EVAL 脚本</li>
 *   <li>惰性初始化：Redis 暂时不可用不会阻塞服务启动；运行时拿不到锁由业务侧降级，
 *       并由数据库唯一键兜底，保证不会超发、不会超领</li>
 *   <li>连接参数复用 spring.data.redis.*（Boot 3 起由 spring.redis.* 改名而来；
 *       与网关、user-service 指向同一 Redis 实例）</li>
 * </ul>
 *
 * @author chronic
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.timeout:2000}") int timeout) {
        Config config = new Config();
        // 启动阶段不建立真实连接：Redis 抖动时服务照常启动，锁调用失败走降级
        config.setLazyInitialization(true)
                .useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setTimeout(timeout)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8);
        if (password != null && !password.trim().isEmpty()) {
            config.useSingleServer().setPassword(password);
        }
        return Redisson.create(config);
    }
}
