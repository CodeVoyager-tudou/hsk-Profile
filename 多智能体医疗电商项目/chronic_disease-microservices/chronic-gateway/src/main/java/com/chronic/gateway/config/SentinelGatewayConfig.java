package com.chronic.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.Set;

/**
 * 网关 Sentinel 限流规则配置
 * <p>
 * 按路由维度设置 QPS 上限，触发限流后由 yml 中 sentinel.scg.fallback 统一返回 429。
 * 各服务限流策略：
 * <ul>
 *   <li>user-service: 100 QPS（用户登录/注册高频入口，峰值需预留余量）</li>
 *   <li>points-service: 100 QPS（签到峰值在每日凌晨，查询频率较高）</li>
 *   <li>shop-service: 50 QPS（药品浏览最高频，下单相对低频）</li>
 *   <li>ai-service: 10 QPS（AI 问诊，第三方模型调用成本高，适度限流）</li>
 * </ul>
 * </p>
 *
 * @author chronic
 */
@Slf4j
@Configuration
public class SentinelGatewayConfig {

    /**
     * 应用启动时加载网关限流规则
     * 每个路由独立 QPS 限制，1 秒滑动窗口，超出则返回 429
     */
    @PostConstruct
    public void initGatewayRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();

        // 用户服务 100 QPS
        rules.add(new GatewayFlowRule("user-service")
                .setCount(100)
                .setIntervalSec(1));

        // 积分服务 100 QPS
        rules.add(new GatewayFlowRule("points-service")
                .setCount(100)
                .setIntervalSec(1));

        // 商城服务 50 QPS
        rules.add(new GatewayFlowRule("shop-service")
                .setCount(50)
                .setIntervalSec(1));

        // 秒杀路由 5 QPS：秒杀瞬时流量极高，入口限流配合 Redis Lua 预扣，
        // 把绝大多数请求挡在 MySQL 之外（超出直接 429，用户端提示稍后再试）
        rules.add(new GatewayFlowRule("shop-seckill")
                .setCount(5)
                .setIntervalSec(1));

        // AI 问诊服务 10 QPS（第三方模型调用成本高，适度限流）
        rules.add(new GatewayFlowRule("ai-service")
                .setCount(10)
                .setIntervalSec(1));

        GatewayRuleManager.loadRules(rules);
        log.info("已加载网关限流规则: user-service=100QPS, points-service=100QPS, shop-service=50QPS, shop-seckill=5QPS, ai-service=10QPS");
    }
}