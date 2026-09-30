package com.chronic.shop;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.mapper.MedicineMapper;
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真库验证 {@code MedicineMapper.updateEditable} 的动态 SQL（复核 P2-14，遗留自第 1 轮）。
 *
 * <p>为什么必须有它：Mockito 只能证明"方法被调、参数对"，证明不了
 * "&lt;set&gt; 动态 SQL 真的只写传入的列"——而"没提交的字段不被改写"正是编辑接口的核心契约
 * （漏验的后果是管理端改个价、别的字段被静默清空）。本用例在真实 MySQL 上跑三条断言：</p>
 * <ol>
 *   <li>只传 price → 其它列一个都不动；</li>
 *   <li>indication 传空串 → 显式清空（null 与空串语义不同的直接证据）；</li>
 *   <li>全空 patch → 不抛 SQL 异常、返回 1（{@code id = id} 兜底的意义所在）。</li>
 * </ol>
 *
 * <p>运行方式与 {@link FlywayMigrationDbTest} 相同（需真实 MySQL，默认跳过）：
 * {@code mvn test -DflywayDbIt=1 -Dtest=MedicineMapperDbTest}。</p>
 *
 * <p>MyBatis-Plus 脱离 Spring 的标准用法（官方文档"单独使用"一节）：
 * {@link MybatisConfiguration} + Environment + {@link MybatisSqlSessionFactoryBuilder}。
 * addMapper 会顺带完成 TableInfo 初始化，Lambda/条件构造器因此可用。</p>
 *
 * <p>库名是写死的字面量（edu_medicine_mapper_it）：它不是用户输入、不需要拼接；
 * 用常量变量拼接反而会被静态扫描当成"标识符拼进 DDL"拦下。</p>
 *
 * @author chronic
 */
@EnabledIfSystemProperty(named = "flywayDbIt", matches = "1")
class MedicineMapperDbTest {

    /** 测试专库（命名与 {@link FlywayMigrationDbTest} 的 edu_shop_flyway_it 同风格），跑完即弃 */
    private static final String JDBC =
            "jdbc:mysql://192.168.100.128:3307/edu_medicine_mapper_it"
                    + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String JDBC_SERVER =
            "jdbc:mysql://192.168.100.128:3307"
                    + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";

    private static SqlSessionFactory factory;

    @BeforeAll
    static void migrateAndBuildSession() throws Exception {
        String user = prop("mysql.user", "root");
        String password = prop("mysql.password", "root");

        // 空库从零迁移：不依赖 edu_shop 里的演示数据，跑完即弃
        try (Connection connection = DriverManager.getConnection(JDBC_SERVER, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS edu_medicine_mapper_it");
            statement.execute("CREATE DATABASE edu_medicine_mapper_it DEFAULT CHARSET utf8mb4");
        }
        Flyway.configure()
                .dataSource(JDBC, user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        DataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", JDBC, user, password);
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("it", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(MedicineMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    static void dropDb() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                JDBC_SERVER, prop("mysql.user", "root"), prop("mysql.password", "root"));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS edu_medicine_mapper_it");
        }
    }

    @Test
    void updateEditable_writesOnlyTheProvidedColumn() {
        try (SqlSession session = factory.openSession(true)) {
            MedicineMapper mapper = session.getMapper(MedicineMapper.class);
            Long id = insertFullMedicine(mapper);

            Medicine patch = new Medicine();
            patch.setPrice(new BigDecimal("66.60"));
            assertEquals(1, mapper.updateEditable(id, patch));

            Medicine after = mapper.selectById(id);
            assertEquals(new BigDecimal("66.60"), after.getPrice(), "price 应被更新");
            assertEquals("测试药", after.getName(), "未提交的 name 不该被改写");
            assertEquals("测试通用名", after.getGenericName());
            assertEquals("测试分类", after.getCategory());
            assertEquals("测试厂家", after.getManufacturer());
            assertEquals("测试适应症", after.getIndication());
            assertEquals(7, after.getStock());
            assertEquals(100, after.getPointsPrice());
            assertEquals(3, after.getPointsReward());
        }
    }

    @Test
    void updateEditable_emptyStringIsAnExplicitClear() {
        try (SqlSession session = factory.openSession(true)) {
            MedicineMapper mapper = session.getMapper(MedicineMapper.class);
            Long id = insertFullMedicine(mapper);

            Medicine patch = new Medicine();
            patch.setIndication("");
            assertEquals(1, mapper.updateEditable(id, patch));

            Medicine after = mapper.selectById(id);
            assertNotNull(after.getIndication());
            assertEquals("", after.getIndication(), "空串 = 显式清空（不是跳过该列）");
            assertEquals("测试药", after.getName(), "其它列不受影响");
        }
    }

    @Test
    void updateEditable_emptyPatchIsSafeAndChangesNothing() {
        try (SqlSession session = factory.openSession(true)) {
            MedicineMapper mapper = session.getMapper(MedicineMapper.class);
            Long id = insertFullMedicine(mapper);

            // 全空 patch（所有字段 null）：调用方已校验"没有要修改的字段"会拒绝，
            // 但 mapper 层仍必须安全 —— <set> 里只剩 id = id 兜底，不能拼出非法 SQL
            assertEquals(1, mapper.updateEditable(id, new Medicine()));

            Medicine after = mapper.selectById(id);
            assertEquals("测试药", after.getName());
            assertEquals(new BigDecimal("12.34"), after.getPrice());
        }
    }

    /** 插入一行全字段药品并回填自增 id */
    private Long insertFullMedicine(MedicineMapper mapper) {
        Medicine medicine = new Medicine();
        medicine.setName("测试药");
        medicine.setGenericName("测试通用名");
        medicine.setCategory("测试分类");
        medicine.setManufacturer("测试厂家");
        medicine.setIndication("测试适应症");
        medicine.setDosage("测试用法");
        medicine.setPrice(new BigDecimal("12.34"));
        medicine.setStock(7);
        medicine.setPointsPrice(100);
        medicine.setPointsReward(3);
        medicine.setStatus(1);
        mapper.insert(medicine);
        assertNotNull(medicine.getId(), "insert 应回填自增 id");
        return medicine.getId();
    }

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
