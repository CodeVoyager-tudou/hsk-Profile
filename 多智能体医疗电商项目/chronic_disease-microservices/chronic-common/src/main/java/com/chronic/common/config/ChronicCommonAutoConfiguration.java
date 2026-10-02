package com.chronic.common.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.chronic.common.oss.OssProperties;
import com.chronic.common.oss.OssStorageService;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * chronic-common 的自动装配（AutoConfiguration.imports 注册，各服务共用）
 * <ul>
 *   <li>traceId 过滤器（仅 Servlet 应用；网关是 WebFlux，不生效）</li>
 *   <li>Feign traceId 透传拦截器（仅引入 OpenFeign 的服务生效）</li>
 *   <li>启动期安全自检（默认口令/弱密钥 fail-fast）</li>
 *   <li>阿里云 OSS 图片存储（仅 classpath 有 SDK 且 aliyun.oss 配置配齐的服务生效）</li>
 * </ul>
 *
 * @author chronic
 */
@Configuration
public class ChronicCommonAutoConfiguration {

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(name = "traceIdFilterRegistration")
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setName("traceIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityHardeningValidator.class)
    public SecurityHardeningValidator securityHardeningValidator(Environment environment) {
        return new SecurityHardeningValidator(environment);
    }

    /**
     * Feign 透传 traceId；嵌套配置类 + @ConditionalOnClass，未引入 Feign 的服务不会碰到该类
     */
    @Configuration
    @ConditionalOnClass(RequestInterceptor.class)
    public static class FeignTraceConfiguration {

        @Bean
        @ConditionalOnMissingBean(TraceIdFeignInterceptor.class)
        public TraceIdFeignInterceptor traceIdFeignInterceptor() {
            return new TraceIdFeignInterceptor();
        }
    }

    /**
     * OSS 自动装配：与上面 Feign 同一套路 —— 嵌套配置类 + @ConditionalOnClass，
     * 没引 SDK 的服务不会碰到 OSS 相关类；再叠加 {@link OssEnabledCondition}，
     * 配了 SDK 但没配 aliyun.oss 四要素的服务也不创建 Bean（服务照常启动，图片接口明确报"未启用"）。
     */
    @Configuration
    @ConditionalOnClass(OSS.class)
    @Conditional(ChronicCommonAutoConfiguration.OssEnabledCondition.class)
    @EnableConfigurationProperties(OssProperties.class)
    public static class OssConfiguration {

        /** OSS 客户端；destroyMethod=shutdown 保证停服时释放连接池 */
        @Bean(destroyMethod = "shutdown")
        @ConditionalOnMissingBean(OSS.class)
        public OSS ossClient(OssProperties properties) {
            return new OSSClientBuilder().build(
                    properties.getEndpoint(), properties.getAccessKeyId(), properties.getAccessKeySecret());
        }

        @Bean
        @ConditionalOnMissingBean
        public OssStorageService ossStorageService(OSS ossClient, OssProperties properties) {
            return new OssStorageService(ossClient, properties);
        }
    }

    /**
     * OSS 生效条件：endpoint、bucket（bucket-name 或 bucketName 两种写法）、
     * access-key-id、access-key-secret 全部非空才生效。
     * 环境变量缺省时占位符解析为空串 → 条件不成立 → 整组 Bean 跳过（宽方向降级，不阻断启动）。
     */
    public static class OssEnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            if (isBlank(env.getProperty("aliyun.oss.endpoint"))
                    || isBlank(env.getProperty("aliyun.oss.access-key-id"))
                    || isBlank(env.getProperty("aliyun.oss.access-key-secret"))) {
                return false;
            }
            return !isBlank(env.getProperty("aliyun.oss.bucket-name"))
                    || !isBlank(env.getProperty("aliyun.oss.bucketName"));
        }

        private boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }
}
