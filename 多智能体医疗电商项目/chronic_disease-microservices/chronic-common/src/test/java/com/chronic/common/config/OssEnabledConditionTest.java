package com.chronic.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OssEnabledCondition 测试：四要素配齐才装配 OSS Bean；
 * 任何一项为空（含环境变量缺省解析出的空串）都跳过装配，服务照常启动。
 *
 * @author chronic
 */
class OssEnabledConditionTest {

    private final ChronicCommonAutoConfiguration.OssEnabledCondition condition =
            new ChronicCommonAutoConfiguration.OssEnabledCondition();

    private MockEnvironment env(String endpoint, String bucket, String id, String secret) {
        MockEnvironment env = new MockEnvironment();
        if (endpoint != null) env.setProperty("aliyun.oss.endpoint", endpoint);
        if (bucket != null) env.setProperty("aliyun.oss.bucket-name", bucket);
        if (id != null) env.setProperty("aliyun.oss.access-key-id", id);
        if (secret != null) env.setProperty("aliyun.oss.access-key-secret", secret);
        return env;
    }

    /** Condition 只用到 getEnvironment()，其余接口给空实现即可 */
    private boolean matches(MockEnvironment environment) {
        return condition.matches(new ConditionContext() {
            @Override public BeanDefinitionRegistry getRegistry() { return null; }
            @Override public ConfigurableListableBeanFactory getBeanFactory() { return null; }
            @Override public Environment getEnvironment() { return environment; }
            @Override public ResourceLoader getResourceLoader() { return null; }
            @Override public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
        }, null);
    }

    @Test
    @DisplayName("四要素齐全 → 生效")
    void allPresent() {
        assertThat(matches(env(
                "https://oss-cn-hangzhou.aliyuncs.com", "qk-parent-xcu", "id", "secret"))).isTrue();
    }

    @Test
    @DisplayName("bucketName 驼峰写法等价于 bucket-name")
    void camelBucketNameAccepted() {
        MockEnvironment env = env(
                "https://oss-cn-hangzhou.aliyuncs.com", null, "id", "secret");
        env.setProperty("aliyun.oss.bucketName", "qk-parent-xcu");
        assertThat(matches(env)).isTrue();
    }

    @Test
    @DisplayName("任何一项缺失/为空串 → 不装配（环境变量缺省占位符解析为空串就是这个场景）")
    void anyBlankSkips() {
        assertThat(matches(env(null, "b", "id", "secret"))).isFalse();
        assertThat(matches(env("https://e.com", "b", "", "secret"))).isFalse();
        assertThat(matches(env("https://e.com", "b", "id", "  "))).isFalse();
        assertThat(matches(env("https://e.com", null, "id", "secret"))).isFalse();
    }
}
