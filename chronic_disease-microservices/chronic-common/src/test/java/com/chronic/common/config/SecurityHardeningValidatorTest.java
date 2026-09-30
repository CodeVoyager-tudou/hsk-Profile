package com.chronic.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SecurityHardeningValidator 回归测试。
 * <p>
 * 重点覆盖 J-01：模拟支付渠道「下单即确认」（{@code chronic.pay.mock-auto-confirm}）
 * 在生产环境等效于零元购 —— 订单不经支付即变为 PAID 并立即发放积分，
 * 积分可兑换实物药品，属可直接批量刷的资损路径。
 * 该配置项默认值为 {@code true}（演示用），因此必须断言其**不得为 true**，
 * 而非仅依赖弱口令清单。
 * </p>
 *
 * @author chronic
 */
class SecurityHardeningValidatorTest {

    /** 足够长且非弱值的 JWT 密钥，避免干扰 J-01 这条断言 */
    private static final String STRONG_JWT_SECRET =
            "a-very-long-and-strong-random-secret-value-for-test-only-32bytes+";

    private SecurityHardeningValidator validator(Map<String, Object> props, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        for (String p : profiles) {
            env.addActiveProfile(p);
        }
        if (!props.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("test", props));
        }
        return new SecurityHardeningValidator(env);
    }

    private Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("J-01: 生产环境开启 mock-auto-confirm=true 必须拒绝启动（零元购）")
    void shouldRejectMockAutoConfirmInProduction() {
        SecurityHardeningValidator v = validator(
                props("chronic.pay.mock-auto-confirm", "true",
                      "chronic.jwt.secret", STRONG_JWT_SECRET),
                "prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("chronic.pay.mock-auto-confirm"),
                "异常信息应点名该项，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("J-01: 生产环境未显式设置该项（落到默认 true）也必须拒绝启动")
    void shouldRejectWhenMockAutoConfirmNotSetInProduction() {
        // 未设置 -> 等同于默认 true，这条正是「运维忘配 = 静默零元购」的防线
        SecurityHardeningValidator v = validator(
                props("chronic.jwt.secret", STRONG_JWT_SECRET), "prod");

        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    @DisplayName("J-01: 生产环境 mock-auto-confirm=false 应放行")
    void shouldAllowMockAutoConfirmDisabledInProduction() {
        SecurityHardeningValidator v = validator(
                props("chronic.pay.mock-auto-confirm", "false",
                      "chronic.jwt.secret", STRONG_JWT_SECRET),
                "prod");

        assertDoesNotThrow(v::afterPropertiesSet);
    }

    @Test
    @DisplayName("J-01: 非生产 profile 下 true 仍放行（本地演示不受影响）")
    void shouldAllowMockAutoConfirmInDev() {
        SecurityHardeningValidator v = validator(
                props("chronic.pay.mock-auto-confirm", "true"), "dev");

        assertDoesNotThrow(v::afterPropertiesSet);
    }

    @Test
    @DisplayName("管理端安全: 生产环境 knife4j.enable=true 必须拒绝启动（接口文档面板）")
    void shouldRejectKnife4jEnabledInProduction() {
        SecurityHardeningValidator v = validator(
                props("chronic.jwt.secret", STRONG_JWT_SECRET,
                      "chronic.pay.mock-auto-confirm", "false",
                      "knife4j.enable", "true"),
                "prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("knife4j.enable"), ex.getMessage());
    }

    @Test
    @DisplayName("管理端安全: 生产环境 knife4j.enable=false 放行")
    void shouldAllowKnife4jDisabledInProduction() {
        SecurityHardeningValidator v = validator(
                props("chronic.jwt.secret", STRONG_JWT_SECRET,
                      "chronic.pay.mock-auto-confirm", "false",
                      "knife4j.enable", "false"),
                "prod");

        assertDoesNotThrow(v::afterPropertiesSet);
    }

    @Test
    @DisplayName("既有行为不回归：生产环境使用内置弱 JWT 密钥仍拒绝启动")
    void shouldStillRejectWeakJwtSecretInProduction() {
        SecurityHardeningValidator v = validator(
                props("chronic.jwt.secret", "chronic-disease-dev-secret-key-2026",
                      "chronic.pay.mock-auto-confirm", "false"),
                "prod");

        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    @DisplayName("既有行为不回归：生产环境弱口令仍被全部点名")
    void shouldStillCollectMultipleWeakValues() {
        SecurityHardeningValidator v = validator(
                props("chronic.jwt.secret", "changeme",
                      "chronic.internal-token", "default_token",
                      "chronic.pay.mock-auto-confirm", "true"),
                "production");

        IllegalStateException ex = assertThrows(IllegalStateException.class, v::afterPropertiesSet);
        String msg = ex.getMessage();
        assertTrue(msg.contains("chronic.jwt.secret"), msg);
        assertTrue(msg.contains("chronic.internal-token"), msg);
        assertTrue(msg.contains("chronic.pay.mock-auto-confirm"), msg);
    }
}
