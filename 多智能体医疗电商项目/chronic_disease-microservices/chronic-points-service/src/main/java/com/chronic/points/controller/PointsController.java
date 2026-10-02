package com.chronic.points.controller;

import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.points.entity.PointsRecord;
import com.chronic.points.entity.SignInRecord;
import com.chronic.points.entity.UserPoints;
import com.chronic.points.mapper.PointsRecordMapper;
import com.chronic.points.service.SignInService;
import com.chronic.points.service.UserPointsService;
import com.chronic.points.vo.WeekSignInVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 积分管理 Controller，提供签到、积分增加、扣减、退还、查询等 REST API
 * <p>
 * 身份与暴露边界：
 * <ul>
 *   <li>签到/查询接口：前端经网关调用，身份取自网关注入的 X-User-Id（JWT 解析结果），
 *       path/param 中的 userId 仅作冗余校验，必须与登录身份一致，杜绝越权；</li>
 *   <li>add/deduct/refund：仅限服务间 Feign 调用。网关路由已不暴露这三个路径，
 *       共享密钥校验由 {@link com.chronic.points.config.InternalApiSecurityConfig}
 *       的拦截器统一完成，Controller 不再重复写。</li>
 * </ul>
 *
 * @author chronic
 */
@Tag(name = "积分管理")
@RestController
@RequestMapping("/points")
@RequiredArgsConstructor
public class PointsController {

    private final UserPointsService userPointsService;
    private final SignInService signInService;
    private final PointsRecordMapper pointsRecordMapper;

    @Operation(summary = "签到")
    @PostMapping("/sign-in")
    public Result<SignInRecord> signIn(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(signInService.signIn(userId));
    }

    @Operation(summary = "本周签到状态(周一~周日循环)")
    @GetMapping("/sign-in/week")
    public Result<WeekSignInVO> getWeekSignIn(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(signInService.getWeekSignIn(userId));
    }

    @Operation(summary = "查询用户积分（仅本人）")
    @GetMapping("/{userId}")
    public Result<UserPoints> getByUserId(@PathVariable Long userId,
                                          @RequestHeader("X-User-Id") Long currentUserId) {
        checkOwner(userId, currentUserId);
        return Result.success(userPointsService.getByUserId(userId));
    }

    @Operation(summary = "查询积分记录（仅本人）")
    @GetMapping("/record/{userId}")
    public Result<List<PointsRecord>> getRecords(@PathVariable Long userId,
                                                 @RequestHeader("X-User-Id") Long currentUserId) {
        checkOwner(userId, currentUserId);
        // 上限保护：流水会随时间无限增长，这里最多返回最近 200 条（分页接口后续按前端需求再加）
        List<PointsRecord> records = pointsRecordMapper.selectList(
                new LambdaQueryWrapper<PointsRecord>()
                        .eq(PointsRecord::getUserId, userId)
                        .orderByDesc(PointsRecord::getCreateTime)
                        .last("LIMIT 200"));
        return Result.success(records);
    }

    @Operation(summary = "增加积分（仅服务间调用，密钥校验见 InternalApiSecurityConfig）")
    @PostMapping("/add")
    public Result<Boolean> addPoints(@RequestParam Long userId,
                                     @RequestParam Integer points,
                                     @RequestParam String type,
                                     @RequestParam(required = false) Long sourceId,
                                     @RequestParam(required = false) String remark) {
        return Result.success(userPointsService.addPoints(userId, points, type, sourceId, remark));
    }

    @Operation(summary = "扣减积分（仅服务间调用，余额不足报错）")
    @PostMapping("/deduct")
    public Result<Boolean> deductPoints(@RequestParam Long userId,
                                        @RequestParam Integer points,
                                        @RequestParam String type,
                                        @RequestParam(required = false) Long sourceId,
                                        @RequestParam(required = false) String remark) {
        return Result.success(userPointsService.deductPoints(userId, points, type, sourceId, remark));
    }

    @Operation(summary = "退还积分（仅服务间调用，兑换取消时使用）")
    @PostMapping("/refund")
    public Result<Boolean> refundPoints(@RequestParam Long userId,
                                        @RequestParam Integer points,
                                        @RequestParam String type,
                                        @RequestParam(required = false) Long sourceId,
                                        @RequestParam(required = false) String remark) {
        return Result.success(userPointsService.refundPoints(userId, points, type, sourceId, remark));
    }

    /** path 中的 userId 必须与登录身份一致 */
    private void checkOwner(Long userId, Long currentUserId) {
        if (!userId.equals(currentUserId)) {
            throw new BusinessException(403, "无权查看他人积分信息");
        }
    }
}