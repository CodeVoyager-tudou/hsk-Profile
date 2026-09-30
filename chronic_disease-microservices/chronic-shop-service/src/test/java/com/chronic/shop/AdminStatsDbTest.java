package com.chronic.shop;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.chronic.shop.mapper.ShopOrderMapper;
import com.chronic.shop.service.impl.ShopOrderServiceImpl;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真库验证 {@code ShopOrderServiceImpl.adminStats()} 的 GROUP BY 下推（复核简报 §4-2，第 2 轮确认遗留）。
 *
 * <p>Mockito 只证明"调用与解析逻辑对"，证明不了<b>聚合 SQL 在真实 MySQL 上的口径</b>：
 * {@code SUM(refund_amount)} 对全 NULL 组返回 NULL、{@code COUNT(*)} 的计数语义、
 * 以及"今日单数"用 {@code create_time > 今天零点}（严格大于）的边界 —— 这些都要见真库。
 * 本用例种入一组手工可算的订单，把 adminStats 的每个字段与手算期望逐一对上：</p>
 *
 * <pre>
 *   种子（create_time 用 NOW() 偏移控制在今天/昨天）：
 *     PAID      今天  10.00          ┐
 *     PAID      今天  15.50          ├ paid=3, gmv = 10.00+15.50+99.99 = 125.49
 *     PAID      昨天  99.99          ┘（昨天那笔不算 today）
 *     PENDING   今天   -
 *     CANCELLED 今天  refund 7.77    ┐ refunded = 7.77 + 0（NULL 组由 decimalOf 转 0）
 *     CANCELLED 昨天  refund NULL    ┘
 *   期望：totalOrders=6, todayOrders=4, paidOrders=3, pendingOrders=1,
 *        cancelledOrders=2, gmv=125.49, refundedAmount=7.77
 * </pre>
 *
 * <p>运行方式与 {@link FlywayMigrationDbTest} 相同：{@code mvn test -DflywayDbIt=1 -Dtest=AdminStatsDbTest}。
 * 库名与种子 SQL 都是写死的字面量（不是用户输入，不做任何拼接）。</p>
 *
 * @author chronic
 */
@EnabledIfSystemProperty(named = "flywayDbIt", matches = "1")
class AdminStatsDbTest {

    private static final String JDBC =
            "jdbc:mysql://192.168.100.128:3307/edu_admin_stats_it"
                    + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String JDBC_SERVER =
            "jdbc:mysql://192.168.100.128:3307"
                    + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";

    private static SqlSessionFactory factory;
    private static String dbUser;
    private static String dbPassword;

    @BeforeAll
    static void migrateAndBuild() throws Exception {
        dbUser = prop("mysql.user", "root");
        dbPassword = prop("mysql.password", "root");

        try (Connection connection = DriverManager.getConnection(JDBC_SERVER, dbUser, dbPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS edu_admin_stats_it");
            statement.execute("CREATE DATABASE edu_admin_stats_it DEFAULT CHARSET utf8mb4");
        }
        Flyway.configure()
                .dataSource(JDBC, dbUser, dbPassword)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        DataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", JDBC, dbUser, dbPassword);
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("it", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(ShopOrderMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    static void dropDb() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_SERVER, dbUser, dbPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS edu_admin_stats_it");
        }
    }

    @Test
    void adminStats_matchesHandComputedExpectationsOnRealMysql() throws Exception {
        seedOrders();

        // adminStats 只用 baseMapper（selectMaps/selectCount），其余依赖传 null 即可。
        // SqlSession 必须在断言完成前保持打开：mapper 代理绑定在 session 上，关了再调就抛异常。
        ShopOrderServiceImpl service = new ShopOrderServiceImpl(
                null, null, null, null, null, null, null, null, null, null, null);
        try (SqlSession session = factory.openSession(true)) {
            ReflectionTestUtils.setField(service, "baseMapper", session.getMapper(ShopOrderMapper.class));

            Map<String, Object> stats = service.adminStats();

            assertEquals(6, stats.get("totalOrders"));
            assertEquals(4, stats.get("todayOrders"), "昨天那笔 PAID 与昨天的 CANCELLED 都不算今日（今天共 4 笔）");
            assertEquals(3, stats.get("paidOrders"));
            assertEquals(1, stats.get("pendingOrders"));
            assertEquals(2, stats.get("cancelledOrders"));
            assertEquals(0, new BigDecimal("125.49").compareTo((BigDecimal) stats.get("gmv")),
                    "gmv 只累计 PAID 的 total_amount（compareTo 免 scale 纠缠）");
            assertEquals(0, new BigDecimal("7.77").compareTo((BigDecimal) stats.get("refundedAmount")),
                    "SUM(refund_amount) 对全 NULL 组应转 0 而不是 NPE/丢数");
        }
    }

    /** 种入手工可算的 6 笔订单（hoursAgo=1 在今天、25 在昨天，避开零点附近的竞态） */
    private void seedOrders() throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC, dbUser, dbPassword);
             PreparedStatement ps = connection.prepareStatement("INSERT INTO shop_order (order_no, user_id, medicine_id, medicine_name, quantity, unit_price, total_amount, points_earned, points_status, pay_type, status, refund_amount, create_time) VALUES (?, 1, 1, '统计种子药', 1, ?, ?, 0, 1, 'CASH', ?, ?, DATE_SUB(NOW(), INTERVAL ? HOUR))")) {
            seed(ps, "it-paid-today-1", "10.00", "10.00", "PAID", null, 1);
            seed(ps, "it-paid-today-2", "15.50", "15.50", "PAID", null, 1);
            seed(ps, "it-paid-yesterday", "99.99", "99.99", "PAID", null, 25);
            seed(ps, "it-pending-today", "5.00", "5.00", "PENDING", null, 1);
            seed(ps, "it-cancelled-today", "7.77", "7.77", "CANCELLED", "7.77", 1);
            seed(ps, "it-cancelled-yesterday", "3.00", "3.00", "CANCELLED", null, 25);
        }
    }

    private void seed(PreparedStatement ps, String orderNo, String unitPrice, String totalAmount,
                      String status, String refund, int hoursAgo) throws Exception {
        ps.setString(1, orderNo);
        ps.setString(2, unitPrice);
        ps.setString(3, totalAmount);
        ps.setString(4, status);
        if (refund == null) {
            ps.setNull(5, java.sql.Types.DECIMAL);
        } else {
            ps.setString(5, refund);
        }
        ps.setInt(6, hoursAgo);
        ps.executeUpdate();
    }

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
