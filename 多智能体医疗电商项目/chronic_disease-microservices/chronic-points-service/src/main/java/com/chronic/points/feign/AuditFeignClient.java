package com.chronic.points.feign;

import com.chronic.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 审计登记客户端：把 points-service 侧的 AI 代查留痕写给 shop-service。
 *
 * <p>{@code admin_audit} 表在 edu_shop 库，本服务连的是 edu_points，跨库直写不可行，
 * 故走这个内部接口（见 shop-service 的 {@code InternalAuditController}）。
 * 调用失败绝不影响本次查询——审计是增强，由 {@code AiAuditRecorder} 兜住并告警。</p>
 *
 * @author chronic
 */
@FeignClient(name = "chronic-shop-service", path = "/internal/audit")
public interface AuditFeignClient {

    /**
     * 登记一条审计
     *
     * @return data=true 表示确实落库；服务不可用时抛出异常（由调用方兜底）
     */
    @PostMapping("/record")
    Result<Boolean> record(@RequestParam("operatorId") Long operatorId,
                           @RequestParam("action") String action,
                           @RequestParam(value = "targetType", required = false) String targetType,
                           @RequestParam(value = "targetId", required = false) Long targetId,
                           @RequestParam(value = "detail", required = false) String detail);
}
