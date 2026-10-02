package com.chronic.points.config;

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
 * 服务间内部接口安全配置（横切关注点统一走拦截器，不在业务代码里逐个校验）。
 *
 * /points/add、/points/deduct、/points/refund 属服务间接口（shop-service 经 Feign 调用）：
 * - 第一层：网关路由不暴露这三个路径；
 * - 第二层：本拦截器校验共享密钥 X-Internal-Token，防绕过网关直连服务端口。
 *
 * 生产环境通过部署机 config/application.yml 覆盖 chronic.internal-token（与 shop-service 一致）。
 * 新增内部接口时把路径加进注册列表即可。
 *
 * @author chronic
 */
@Configuration
public class InternalApiSecurityConfig implements WebMvcConfigurer {

    /** 服务间共享密钥；为空时按"未配置"处理，一律拒绝（fail-closed） */
    @Value("${chronic.internal-token:}")
    private String internalToken;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new InternalTokenInterceptor())
                .addPathPatterns("/points/add", "/points/deduct", "/points/refund",
                        "/account/deduct", "/account/refund",
                        // AI 服务查询用户资产（余额/积分）走这里，同样是内网直连、无网关兜底
                        "/internal/**");
    }

    /** 校验 X-Internal-Token 共享密钥；缺密钥/不匹配一律 403（fail-closed） */
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
         * 常量时间比较共享密钥（J-09 修复）。
         *
         * <p>原先写的是 {@code internalToken.equals(token)}。String.equals 会
         * <b>逐字符比较、遇到第一个不同就立刻返回</b>，因此比较耗时与
         * "前多少个字符猜对了"成正比 —— 攻击者可以通过反复测量响应时间，
         * 一个字符一个字符地把密钥试出来（时序侧信道）。</p>
         *
         * <p>{@link MessageDigest#isEqual} 无论内容是否相同都遍历完整长度，
         * 耗时恒定，能消除该侧信道。同一仓库的
         * {@code ShopOrderController.matchesInternalToken} 早已这么做了，
         * 此处与之对齐。</p>
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
