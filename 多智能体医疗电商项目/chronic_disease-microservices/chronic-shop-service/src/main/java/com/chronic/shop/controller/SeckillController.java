package com.chronic.shop.controller;

import com.chronic.common.exception.BusinessException;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.SeckillActivity;
import com.chronic.shop.service.SeckillCaptchaService;
import com.chronic.shop.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 秒杀 Controller：滑块验证码 / 活动列表 / 抢购 / 结果轮询。
 * <p>网关为 /api/shop/seckill/** 配置了独立低阈值限流（见 gateway SentinelGatewayConfig）；
 * 抢购还需先过滑块验证（一次性票据），前置拦截 + 预扣 + 限流构成三层漏斗。</p>
 *
 * @author chronic
 */
@Tag(name = "秒杀")
@RestController
@RequestMapping("/shop/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final SeckillCaptchaService captchaService;

    @Operation(summary = "秒杀活动列表（前端按 start/end 时间区分状态）")
    @GetMapping("/list")
    public Result<List<SeckillActivity>> list() {
        return Result.success(seckillService.listActivities());
    }

    @Operation(summary = "获取滑块验证码（登录后调用；返回底图/碎块 PNG base64 与碎块纵坐标）")
    @GetMapping("/captcha")
    public Result<SeckillCaptchaService.Captcha> captcha(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(captchaService.generate(userId));
    }

    @Operation(summary = "校验滑块（轨迹行为校验 + 位置容差 ±6px；通过签发一次性抢购票据）")
    @PostMapping("/captcha/verify")
    public Result<String> verify(@RequestParam String captchaId,
                                 @RequestParam Integer x,
                                 @RequestParam String trajectory,
                                 @RequestParam Long durationMs,
                                 @RequestHeader("X-User-Id") Long userId) {
        return Result.success(captchaService.verify(userId, captchaId, x, trajectory, durationMs));
    }

    @Operation(summary = "抢购（需滑块票据 ticket；Lua 原子预扣 + MQ 排队，结果走 result 轮询）")
    @PostMapping("/{activityId}")
    public Result<String> seckill(@PathVariable Long activityId,
                                  @RequestParam(required = false) String ticket,
                                  @RequestHeader("X-User-Id") Long userId) {
        return Result.success(seckillService.seckill(userId, activityId, ticket));
    }

    @Operation(summary = "抢购结果轮询（null=处理中；SUCCESS:{orderNo} / FAILED:{原因}）")
    @GetMapping("/result/{activityId}")
    public Result<String> result(@PathVariable Long activityId,
                                 @RequestHeader("X-User-Id") Long userId) {
        return Result.success(seckillService.getResult(userId, activityId));
    }
}
