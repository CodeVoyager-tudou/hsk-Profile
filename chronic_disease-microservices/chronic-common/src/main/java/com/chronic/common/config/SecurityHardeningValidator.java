package com.chronic.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 启动期安全自检：杜绝"忘记配环境变量 -> 静默使用弱默认口令"这条最危险的路径
 * <p>
 * 规则：
 * <ul>
 *   <li>prod/production profile 下，命中内置弱口令清单或 JWT 密钥不足 32 字节 -> 直接拒绝启动（fail-fast）</li>
 *   <li>非生产 profile 下只打印告警，方便本地开发</li>
 *   <li>只输出配置项名称，绝不回显密钥内容</li>
 * </ul>
 * 配置项缺失时 Spring 的占位符解析本身就会失败，等于另一层 fail-fast。
 *
 * @author chronic
 */
@Slf4j
public class SecurityHardeningValidator implements InitializingBean {

    /** 项目历史上出现过的默认口令/弱口令（小写比较） */
    private static final Set<String> KNOWN_WEAK_VALUES = Set.of(
            "chronic-disease-dev-secret-key-2026",
            "dev-internal-token-2026",
            "default_token",
            "1234",
            "123456",
            "root",
            "password",
            "changeme",
            "secret");

    /** 需要巡检的敏感配置项；未使用该项的服务会自动跳过 */
    private static final List<String> SENSITIVE_KEYS = Arrays.asList(
            "chronic.jwt.secret",
            "chronic.internal-token",
            "chronic.pay.mock-auto-confirm",
            "xxl.job.accessToken",
            "spring.data.redis.password",
            "spring.datasource.password",
            "spring.datasource.username");

    private static final int MIN_JWT_SECRET_BYTES = 32;

    private final Environment environment;

    public SecurityHardeningValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));

        List<String> weakKeys = new ArrayList<>();
        for (String key : SENSITIVE_KEYS) {
            String value = environment.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (KNOWN_WEAK_VALUES.contains(value.trim().toLowerCase(Locale.ROOT))) {
                weakKeys.add(key);
            }
        }

        String jwtSecret = environment.getProperty("chronic.jwt.secret");
        boolean jwtTooShort = jwtSecret != null && jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES;
        if (jwtTooShort) {
            weakKeys.add("chronic.jwt.secret(长度不足" + MIN_JWT_SECRET_BYTES + "字节)");
        }

        // J-01：模拟支付渠道「下单即确认」在生产环境等效于零元购 ——
        // 订单不经任何支付即变为 PAID 并立即发放积分，积分可兑换实物药品，属可直接批量刷的资损路径。
        // 该项默认值为 true（演示用），因此必须**断言其不得为 true**，而不是仅检查弱口令清单。
        // 默认值亦受检：运维若不显式设置 PAY_MOCK_AUTO_CONFIRM，落到 true 时生产环境将拒绝启动。
        String mockAutoConfirm = environment.getProperty("chronic.pay.mock-auto-confirm");
        boolean mockPayEnabled = mockAutoConfirm == null || "true".equalsIgnoreCase(mockAutoConfirm.trim());
        if (mockPayEnabled) {
            weakKeys.add("chronic.pay.mock-auto-confirm(模拟支付下单即确认=零元购，生产必须置 false)");
        }

        // 接口文档面板（knife4j/swagger）生产环境必须显式关闭：
        // 它会暴露全部接口清单、参数结构与在线调试入口，等于给攻击者送完整地图。
        // 各服务 yml 已把该键改为 ${KNIFE4J_ENABLE:true}——生产注入 KNIFE4J_ENABLE=false 即通过；
        // 属性为 "true"（含缺省注入值）时生产拒绝启动。
        if (production && "true".equalsIgnoreCase(environment.getProperty("knife4j.enable", ""))) {
            weakKeys.add("knife4j.enable(生产必须置 false，注入 KNIFE4J_ENABLE=false)");
        }

        if (weakKeys.isEmpty()) {
            log.info("安全自检通过：未发现默认口令/弱密钥");
            return;
        }
        if (production) {
            throw new IllegalStateException("生产环境安全自检失败，以下配置仍是默认值/弱值，请用环境变量或外部配置注入强随机值后重启: "
                    + String.join(", ", weakKeys));
        }
        log.warn("安全自检告警（生产 profile 下将直接拒绝启动）：以下配置为开发默认值/弱值 -> {}；"
                + "生产请用环境变量注入强随机值（见 .env.example 与 scripts/local-env.ps1）", String.join(", ", weakKeys));
    }
}
