package com.chronic.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口安全配置（/internal/**）：只允许内网服务调用，不经过网关暴露给前端。
 *
 * <p>身份靠共享密钥 {@code X-Internal-Token} 而不是调用方自填的 {@code X-User-Id}——
 * 后端服务的端口在内网是可直接访问的，若只认 {@code X-User-Id}，任何能连上 8083 的
 * 进程都能冒充任意用户读订单。{@code X-User-Id} 只作"查谁的订单"这个参数用。</p>
 *
 * <p>实现与 points-service 的 {@code InternalApiSecurityConfig} 同构（那份保护
 * /points、/account 的内部接口）。两份现在是各自独立的复制品，若再加服务，
 * 值得抽到 chronic-common 里做成可注册的组件。</p>
 *
 * @author chronic
 */
@Configuration
public class InternalApiSecurityConfig implements WebMvcConfigurer {

    /** 服务间共享密钥（与 points-service / AI 服务同一个值）；为空时按"未配置"处理，一律拒绝 */
    @Value("${chronic.internal-token:}")
    private String internalToken;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new InternalTokenInterceptor())
                .addPathPatterns("/internal/**");
    }

    /** 校验 X-Internal-Token；缺密钥/不匹配一律 403（fail-closed） */
    private class InternalTokenInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                 Object handler) throws IOException {
            String token = request.getHeader("X-Internal-Token");
            if (internalToken != null && !internalToken.isBlank() && constantTimeEquals(internalToken, token)) {
                return true;
            }
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"内部接口禁止外部调用\"}");
            return false;
        }

        /**
         * 常量时间比较共享密钥：String.equals 逐字符比较、遇到不同立刻返回，
         * 耗时与"猜对多少字符"相关，可被时序侧信道逐字符试探（J-09 同款问题）。
         * {@link MessageDigest#isEqual} 恒定耗时，消除该侧信道。
         */
        private boolean constantTimeEquals(String expected, String provided) {
            if (provided == null) {
                return false;
            }
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    provided.getBytes(StandardCharsets.UTF_8));
        }
    }
}
