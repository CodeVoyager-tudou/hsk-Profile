package com.chronic.points.controller;

import com.chronic.common.result.Result;
import com.chronic.points.entity.UserAccount;
import com.chronic.points.entity.UserPoints;
import com.chronic.points.service.AiAuditRecorder;
import com.chronic.points.service.UserAccountService;
import com.chronic.points.service.UserPointsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 内部资产查询接口：供 AI 回答"我有多少积分 / 余额多少"。
 *
 * <p>刻意与 {@code AccountController}/{@code PointsController} 里"仅本人"的那几个接口分开：
 * 那几个靠网关注入的 {@code X-User-Id} 自证身份，AI 服务是内网直连、没有网关这一层，
 * 所以走 {@code /internal/**} 前缀 + 共享令牌（见 {@code InternalApiSecurityConfig}），
 * {@code userId} 只作查询参数。</p>
 *
 * @author chronic
 */
@Tag(name = "内部-资产")
@RestController
@RequestMapping("/internal/asset")
@RequiredArgsConstructor
public class InternalAssetController {

    private final UserAccountService userAccountService;
    private final UserPointsService userPointsService;
    private final AiAuditRecorder aiAuditRecorder;

    @Operation(summary = "查询余额账户（内部）")
    @GetMapping("/account/{userId}")
    public Result<UserAccount> account(@PathVariable Long userId) {
        UserAccount account = userAccountService.getByUserId(userId);
        // AI 代用户查余额要留痕：读的是个人资金数据，属于需要可追溯的敏感操作
        aiAuditRecorder.recordAssetQuery(userId, "account");
        return Result.success(account);
    }

    @Operation(summary = "查询积分账户（内部）")
    @GetMapping("/points/{userId}")
    public Result<UserPoints> points(@PathVariable Long userId) {
        UserPoints points = userPointsService.getByUserId(userId);
        aiAuditRecorder.recordAssetQuery(userId, "points");
        return Result.success(points);
    }
}
