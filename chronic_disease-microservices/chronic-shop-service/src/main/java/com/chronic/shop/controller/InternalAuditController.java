package com.chronic.shop.controller;

import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.shop.service.AdminAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 内部审计写入接口：把审计做成"一个地方落库"，供写不到 {@code edu_shop} 库的服务登记审计。
 *
 * <h3>为什么需要它</h3>
 * {@code admin_audit} 表在 shop-service 自己的库（edu_shop）里。points-service 查了
 * 用户的积分/余额后也应当留痕，但它连的是 edu_points，跨库直写不可行；此前只能在日志里
 * 打一行（既不好查，也不在管理端"操作审计"页面上）。与其在两处各建一张审计表，
 * 不如让 points-service 通过本接口把审计登记过来，保持"审计只有一份、可统一查询"。
 *
 * <p>鉴权沿用 {@code /internal/**} 的内部令牌拦截器（见 {@code InternalApiSecurityConfig}），
 * 与业务内部接口同一道闸门；审计写入失败不会抛给调用方，只返回 false 让其自行告警。</p>
 *
 * <p>入参约束（复核 P2-3）：列约束是 {@code action VARCHAR(60) NOT NULL}、
 * {@code target_type VARCHAR(30) NOT NULL}——接口签名若承诺"可选"、实现却必填，
 * 或放行超长值让插入失败后被 {@code tryRecord} 吞掉，审计都会静默丢失。
 * 所以这里在入口把约束说清楚：必填、限长、action 白名单。</p>
 *
 * @author chronic
 */
@Slf4j
@Tag(name = "内部-审计")
@RestController
@RequestMapping("/internal/audit")
@RequiredArgsConstructor
public class InternalAuditController {

    private final AdminAuditService adminAuditService;

    /** 与 admin_audit 列约束对齐：action VARCHAR(60)、target_type VARCHAR(30) */
    private static final int MAX_ACTION_LEN = 60;
    private static final int MAX_TARGET_TYPE_LEN = 30;

    /**
     * 允许登记的 action 白名单：内部令牌持有者不应能写任意 action——
     * 将来按 action 做告警/统计时，脏值会把口径带偏。新增动作时在此登记。
     */
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "AI_QUERY_ORDER_LIST", "AI_QUERY_ORDER_SUMMARY", "AI_QUERY_ORDER_DETAIL",
            "AI_QUERY_ASSET", "AI_QUERY_COUPONS", "AI_CANCEL_ORDER");

    @Operation(summary = "登记一条审计（内部）：返回是否真的落库")
    @PostMapping("/record")
    public Result<Boolean> record(@RequestParam Long operatorId,
                                  @RequestParam String action,
                                  @RequestParam String targetType,
                                  @RequestParam(required = false) Long targetId,
                                  @RequestParam(required = false) String detail) {
        if (action == null || action.isBlank()) {
            throw new BusinessException("action 必填");
        }
        if (action.length() > MAX_ACTION_LEN) {
            throw new BusinessException("action 长度不能超过 " + MAX_ACTION_LEN);
        }
        if (!ALLOWED_ACTIONS.contains(action)) {
            log.warn("拒绝登记白名单外的审计 action: action={}, operator={}", action, operatorId);
            throw new BusinessException("action 不在白名单内");
        }
        if (targetType == null || targetType.isBlank()) {
            // 列是 NOT NULL：不在这里拒绝，插入会失败并被 tryRecord 吞掉 —— 审计静默丢失
            throw new BusinessException("targetType 必填（admin_audit.target_type 为 NOT NULL）");
        }
        if (targetType.length() > MAX_TARGET_TYPE_LEN) {
            throw new BusinessException("targetType 长度不能超过 " + MAX_TARGET_TYPE_LEN);
        }
        return Result.success(adminAuditService.tryRecord(operatorId, action, targetType, targetId, detail));
    }
}
