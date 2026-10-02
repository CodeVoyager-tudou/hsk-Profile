package com.chronic.shop.feign;

import com.alibaba.csp.sentinel.util.StringUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.chronic.common.exception.BusinessException;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Feign 错误解码：把下游（points-service 等）返回的「HTTP 4xx/5xx + Result 错误体」
 * 还原成带真实原因的 {@link BusinessException}，而不是让 Feign 默认抛一个丢失信息的
 * FeignException。
 *
 * <h3>【修的是什么问题】</h3>
 * 服务间约定「业务失败返回 HTTP 200 + Result.code != 200」，但下游的 GlobalExceptionHandler
 * 会把未捕获的 BusinessException 映射成 HTTP 400 + JSON 错误体。Feign 对一切非 2xx 响应
 * 直接抛 FeignException——调用方的 {@code catch (BusinessException)} 接不住，只能落进
 * {@code catch (Exception)} 的"结果不明"分支。表象就是：用户积分明明不够，
 * 页面却提示"积分服务暂时不可用，请稍后重试"（余额不足同理）——把业务规则误报成了系统故障。
 *
 * <h3>【解码后的行为】</h3>
 * 响应体能解析出 {code, message} → 抛 BusinessException(code, message)，
 * 调用方按业务失败处理（回滚本地事务、如实提示"积分余额不足"）；
 * 解析不出（网关超时页、HTML 错误页、连接被拒）→ 交回 Feign 默认解码器，
 * 仍按"服务不可用"的保守分支处理。
 */
@Slf4j
@Configuration
public class FeignErrorDecoderConfig {

    @Bean
    public ErrorDecoder feignBusinessErrorDecoder() {
        return (String methodKey, Response response) -> {
            String bodyText = readBody(response);
            Integer code = null;
            String message = null;
            if (StringUtil.isNotBlank(bodyText) && bodyText.trim().startsWith("{")) {
                try {
                    JSONObject json = JSONUtil.parseObj(bodyText);
                    if (json.containsKey("code") && json.containsKey("message")) {
                        code = json.getInt("code");
                        message = json.getStr("message");
                    }
                } catch (Exception e) {
                    log.debug("Feign 错误体不是 Result JSON，走默认解码: {}", methodKey);
                }
            }
            if (code != null) {
                // 下游的业务规则失败（积分/余额不足、状态不允许等）如实上抛，
                // 让调用方的 catch (BusinessException) 能按业务分支处理
                log.info("Feign 业务失败透传: method={}, httpStatus={}, code={}, message={}",
                        methodKey, response.status(), code, message);
                return new BusinessException(code == null ? 400 : code,
                        message == null ? "下游服务返回业务错误" : message);
            }
            log.warn("Feign 调用失败（非业务错误体）: method={}, httpStatus={}, body={}",
                    methodKey, response.status(),
                    bodyText == null ? "" : bodyText.substring(0, Math.min(bodyText.length(), 200)));
            return new ErrorDecoder.Default().decode(methodKey, response);
        };
    }

    /** 读出错误响应体文本（流只能读一次，读失败不阻塞默认解码路径） */
    private String readBody(Response response) {
        try (InputStream is = response.body().asInputStream()) {
            byte[] bytes = is.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
