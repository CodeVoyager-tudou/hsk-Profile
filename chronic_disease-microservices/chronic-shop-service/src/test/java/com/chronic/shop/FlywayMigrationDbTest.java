package com.chronic.shop;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 MySQL 上的迁移验证（默认跳过，需 -DflywayDbIt=1 显式开启，避免无数据库的 CI 失败）：
 * <ol>
 *   <li>空库从零迁移，断言关键表/列/索引存在</li>
 *   <li>重复迁移，断言不再执行任何脚本（可重复部署）</li>
 *   <li>删掉 flyway_schema_history 后再迁移一次，模拟"老库首次接入 Flyway"，
 *       所有脚本必须幂等不报错、且不破坏已有数据</li>
 * </ol>
 *
 * @author chronic
 */
@EnabledIfSystemProperty(named = "flywayDbIt", matches = "1")
class FlywayMigrationDbTest {

    private static final String DB = "edu_shop_flyway_it";

    @Test
    void migrateFreshRepeatedAndLegacyDatabaseIsIdempotent() throws Exception {
        String host = prop("mysql.host", "192.168.100.128");
        int port = Integer.parseInt(prop("mysql.port", "3307"));
        String user = prop("mysql.user", "root");
        String password = prop("mysql.password", "root");
        String adminUrl = jdbc(host, port, "");
        String url = jdbc(host, port, DB);

        try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB);
            statement.execute("CREATE DATABASE " + DB + " DEFAULT CHARSET utf8mb4");
        }
        try {
            MigrateResult first = flyway(url, user, password).migrate();
            assertTrue(first.migrationsExecuted >= 1, "首次迁移应执行脚本，实际 " + first.migrationsExecuted);
            assertTableExists(url, user, password, DB, "medicine");
            assertTableExists(url, user, password, DB, "shop_order");
            assertTableExists(url, user, password, DB, "coupon");
            assertTableExists(url, user, password, DB, "user_coupon");
            assertTableExists(url, user, password, DB, "compensation_task");
            assertColumnExists(url, user, password, DB, "user_coupon", "receive_no");
            assertColumnExists(url, user, password, DB, "shop_order", "request_id");
            assertColumnExists(url, user, password, DB, "shop_order", "pay_time");
            assertIndexExists(url, user, password, DB, "user_coupon", "uk_user_coupon_user_coupon_no");
            // J-02：幂等唯一键已由 (request_id) 改为 (user_id, request_id)，
            // 避免跨用户复用同一 requestId 时撞全表唯一键而返回 500。
            assertIndexExists(url, user, password, DB, "shop_order", "uk_user_order_request");
            assertIndexNotExists(url, user, password, DB, "shop_order", "uk_order_request");
            // J-04：补偿台账新增生成列 active_flag 与「仅约束 PENDING」的唯一键，
            // 使 FAILED 任务不再永久占用唯一键（原实现会导致该订单再也无法建补偿账）。
            assertColumnExists(url, user, password, DB, "compensation_task", "active_flag");
            assertIndexExists(url, user, password, DB, "compensation_task", "uk_biz_active");
            assertIndexNotExists(url, user, password, DB, "compensation_task", "uk_biz_type_biz_id");
            assertIndexExists(url, user, password, DB, "shop_order", "idx_user_create_time");
            // J-13：补齐订单查询复合索引
            assertIndexExists(url, user, password, DB, "shop_order", "idx_status_create_time");
            assertIndexExists(url, user, password, DB, "shop_order", "idx_points_grant_scan");
            // J-14：订单事件本地消息表（outbox）
            assertTableExists(url, user, password, DB, "order_event_outbox");
            assertColumnExists(url, user, password, DB, "order_event_outbox", "retry_count");
            assertColumnExists(url, user, password, DB, "order_event_outbox", "sent_time");
            // 幂等唯一键：同一订单的同一类事件只允许一条，保证重试不会重复投递
            assertIndexExists(url, user, password, DB, "order_event_outbox", "uk_order_event");
            // 中继任务按「状态 + 下次重试时间」扫描
            assertIndexExists(url, user, password, DB, "order_event_outbox", "idx_status_next_retry");
            assertIndexExists(url, user, password, DB, "user_coupon", "idx_user_receive_time");
            assertEquals(12, count(url, user, password, "SELECT COUNT(*) FROM medicine"), "种子数据应写入");

            // 重复迁移：不应执行任何脚本
            assertEquals(0, flyway(url, user, password).migrate().migrationsExecuted, "重复迁移不应重复执行脚本");

            // 模拟老库（表已存在但没有历史表）首次接入 Flyway
            execute(url, user, password, "DROP TABLE flyway_schema_history");
            MigrateResult legacy = flyway(url, user, password).migrate();
            assertTrue(legacy.migrationsExecuted >= 1, "老库接入应重新建立历史并执行脚本");
            assertTableExists(url, user, password, DB, "medicine");
            assertTableExists(url, user, password, DB, "shop_order");
            assertTableExists(url, user, password, DB, "coupon");
            assertTableExists(url, user, password, DB, "user_coupon");
            assertTableExists(url, user, password, DB, "compensation_task");
            assertColumnExists(url, user, password, DB, "user_coupon", "receive_no");
            assertColumnExists(url, user, password, DB, "shop_order", "request_id");
            assertColumnExists(url, user, password, DB, "shop_order", "pay_time");
            assertEquals(12, count(url, user, password, "SELECT COUNT(*) FROM medicine"), "老库路径不应破坏种子数据");
        } finally {
            try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + DB);
            }
        }
    }

    private static String prop(String key, String defaultValue) {
        String value = System.getProperty(key);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    private static String jdbc(String host, int port, String db) {
        return "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&allowMultiQueries=false";
    }

    private static Flyway flyway(String url, String user, String password) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    private static void execute(String url, String user, String password, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(String url, String user, String password, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "查询应返回一行: " + sql);
            return rs.getLong(1);
        }
    }

    private static void assertTableExists(String url, String user, String password, String db, String table)
            throws Exception {
        assertEquals(1, count(url, user, password,
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + db + "' AND TABLE_NAME='" + table + "'"),
                "表应存在: " + table);
    }

    private static void assertColumnExists(String url, String user, String password, String db, String table, String column)
            throws Exception {
        assertEquals(1, count(url, user, password,
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + db
                        + "' AND TABLE_NAME='" + table + "' AND COLUMN_NAME='" + column + "'"),
                "列应存在: " + table + "." + column);
    }

    private static void assertIndexExists(String url, String user, String password, String db, String table, String index)
            throws Exception {
        assertEquals(1, count(url, user, password,
                "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='" + db
                        + "' AND TABLE_NAME='" + table + "' AND INDEX_NAME='" + index + "'"),
                "索引应存在: " + table + "." + index);
    }

    /** 断言索引已不存在（用于验证 J-02/J-04 中旧唯一键已被移除） */
    private static void assertIndexNotExists(String url, String user, String password, String db, String table, String index)
            throws Exception {
        assertEquals(0, count(url, user, password,
                "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='" + db
                        + "' AND TABLE_NAME='" + table + "' AND INDEX_NAME='" + index + "'"),
                "索引应已被移除: " + table + "." + index);
    }
}
