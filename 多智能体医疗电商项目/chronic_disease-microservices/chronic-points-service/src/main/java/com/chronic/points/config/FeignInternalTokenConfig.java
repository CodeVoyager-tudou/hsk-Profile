package com.chronic.points.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 服务间调用配置：为调用 shop-service 的请求附加内部共享密钥。
 *
 * <p>与 shop-service 的同名配置同构：审计登记接口挂在 {@code /internal/**} 下，
 * 由那边的 {@code InternalApiSecurityConfig} 校验该密钥（fail-closed）。
 * 这里注册成普通 Bean 即对所有 Feign 客户端生效，无需逐个 {@code @FeignClient(configuration=...)}。</p>
 *
 * @author chronic
 */
@Configuration
public class FeignInternalTokenConfig {

    @Value("${chronic.internal-token:}")
    private String internalToken;

    @Bean
    public RequestInterceptor internalTokenInterceptor() {
        return template -> {
            if (internalToken != null && !internalToken.isBlank()) {
                template.header("X-Internal-Token", internalToken);
            }
        };
    }
}
