package com.chronic.shop.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 服务间调用配置：为调用 points-service 的请求附加内部共享密钥。
 * points-service 的 add/deduct/refund 接口校验该密钥，防止外部绕过网关直连。
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
