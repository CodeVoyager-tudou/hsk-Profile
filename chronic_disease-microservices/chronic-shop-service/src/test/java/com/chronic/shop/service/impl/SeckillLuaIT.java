package com.chronic.shop.service.impl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 秒杀 Lua 脚本集成测试（真实 Redis）。
 *
 * <p>默认跳过；跑真实 Redis 时加系统属性：<code>-DseckillRedisIt=1</code>
 * （与 FlywayMigrationDbTest 的 flywayDbIt 同一套路）。验证的是脚本本身的语义：
 * 原子预扣、一人一单、库存不足、回补——这些逻辑在 mock 里测不到。</p>
 *
 * @author chronic
 */
@EnabledIfSystemProperty(named = "seckillRedisIt", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeckillLuaIT {

    private static final String HOST = prop("redis.host", "192.168.100.128");
    private static final int PORT = Integer.parseInt(prop("redis.port", "6379"));
    private static final String PASSWORD = prop("redis.password", "1234");

    private static final String SECKILL_LUA =
            "if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 1 end " +
            "local stock = tonumber(redis.call('GET', KEYS[1])) " +
            "if stock == nil then return 2 end " +
            "if stock < tonumber(ARGV[2]) then return 2 end " +
            "redis.call('DECRBY', KEYS[1], ARGV[2]) " +
            "redis.call('SADD', KEYS[2], ARGV[1]) " +
            "return 0";

    private static final String RESTOCK_LUA =
            "redis.call('INCRBY', KEYS[1], ARGV[2]) " +
            "redis.call('SREM', KEYS[2], ARGV[1]) " +
            "return 1";

    /** 独立 key 前缀 + 时间戳，避免污染/受扰运行数据 */
    private final String stockKey = "it:seckill:stock:" + System.currentTimeMillis();
    private final String usersKey = "it:seckill:users:" + System.currentTimeMillis();

    private RedissonClient redisson;

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : v;
    }

    @BeforeAll
    void connect() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + HOST + ":" + PORT)
                .setPassword(PASSWORD.isEmpty() ? null : PASSWORD)
                .setConnectTimeout(3000)
                .setTimeout(3000);
        redisson = Redisson.create(config);
    }

    @AfterAll
    void cleanup() {
        try {
            redisson.getKeys().delete(stockKey, usersKey);
        } finally {
            redisson.shutdown();
        }
    }

    private long runLua(String lua, String userId) {
        RScript script = redisson.getScript(StringCodec.INSTANCE);
        return (Long) script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.INTEGER,
                Arrays.asList(stockKey, usersKey), userId, "1");
    }

    @Test
    void luaShouldPassDuplicateSoldOutAndRestock() {
        redisson.getBucket(stockKey, StringCodec.INSTANCE).set("2"); // 总名额 2

        assertEquals(0L, runLua(SECKILL_LUA, "u1")); // u1 预扣成功（剩 1）
        assertEquals(0L, runLua(SECKILL_LUA, "u2")); // u2 预扣成功（剩 0）
        assertEquals(1L, runLua(SECKILL_LUA, "u1")); // u1 重复 → 一人一单拦截
        assertEquals(2L, runLua(SECKILL_LUA, "u3")); // 库存不足

        assertEquals(1L, runLua(RESTOCK_LUA, "u2")); // u2 回补（剩 1，u2 移出已购名单）
        assertEquals(0L, runLua(SECKILL_LUA, "u3")); // u3 现在能抢到
        assertEquals(1L, runLua(SECKILL_LUA, "u3")); // u3 再抢 → 重复拦截
    }
}
