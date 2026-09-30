package com.chronic.points.controller;

import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.points.entity.AccountRecord;
import com.chronic.points.entity.UserAccount;
import com.chronic.points.service.UserAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 余额账户 Controller
 * <p>
 * 身份与暴露边界（与积分 Controller 同一套规则）：
 * <ul>
 *   <li>recharge / 查询接口：前端经网关调用，身份取自网关注入的 X-User-Id，
 *       path/param 中的 userId 仅作冗余校验，必须与登录身份一致，杜绝越权；</li>
 *   <li>deduct / refund：仅限服务间 Feign 调用（shop 支付/退款）。网关路由不暴露这两个路径，
 *       密钥校验由 InternalApiSecurityConfig 的拦截器统一完成。</li>
 * </ul>
 *
 * @author chronic
 */
@Tag(name = "余额账户")
@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountController {

    private final UserAccountService userAccountService;

    @Operation(summary = "充值（模拟支付渠道：等价于'第三方支付成功回调后入账'）")
    @PostMapping("/recharge")
    public Result<BigDecimal> recharge(@RequestHeader("X-User-Id") Long userId,
                                       @RequestParam BigDecimal amount) {
        return Result.success(userAccountService.recharge(userId, amount, "模拟充值"));
    }

    @Operation(summary = "查询余额账户（仅本人）")
    @GetMapping("/{userId}")
    public Result<UserAccount> getAccount(@PathVariable Long userId,
                                          @RequestHeader("X-User-Id") Long currentUserId) {
        checkOwner(userId, currentUserId);
        return Result.success(userAccountService.getByUserId(userId));
    }

    @Operation(summary = "查询余额流水（仅本人，最近 200 条）")
    @GetMapping("/record/{userId}")
    public Result<List<AccountRecord>> getRecords(@PathVariable Long userId,
                                                  @RequestHeader("X-User-Id") Long currentUserId) {
        checkOwner(userId, currentUserId);
        return Result.success(userAccountService.getRecords(userId, 200));
    }

    @Operation(summary = "余额扣款（仅服务间调用：余额支付；余额不足报业务错误）")
    @PostMapping("/deduct")
    public Result<Boolean> deductBalance(@RequestParam Long userId,
                                         @RequestParam BigDecimal amount,
                                         @RequestParam String type,
                                         @RequestParam Long sourceId,
                                         @RequestParam(required = false) String remark) {
        return Result.success(userAccountService.deductBalance(userId, amount, type, sourceId, remark));
    }

    @Operation(summary = "余额退款（仅服务间调用：订单取消/补偿回款）")
    @PostMapping("/refund")
    public Result<Boolean> refundBalance(@RequestParam Long userId,
                                         @RequestParam BigDecimal amount,
                                         @RequestParam String type,
                                         @RequestParam Long sourceId,
                                         @RequestParam(required = false) String remark) {
        return Result.success(userAccountService.refundBalance(userId, amount, type, sourceId, remark));
    }

    /** path 中的 userId 必须与登录身份一致 */
    private void checkOwner(Long userId, Long currentUserId) {
        if (!userId.equals(currentUserId)) {
            throw new BusinessException(403, "无权查看他人账户信息");
        }
    }
}
