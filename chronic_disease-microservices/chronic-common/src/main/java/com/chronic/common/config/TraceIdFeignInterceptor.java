package com.chronic.common.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

/**
 * Feign 调用透传 traceId：跨服务调用共用同一个 traceId，日志可串起来。
 *
 * @author chronic
 */
public class TraceIdFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        if (traceId != null && !traceId.isEmpty()) {
            template.header(TraceIdFilter.TRACE_ID_HEADER, traceId);
        }
    }
}
