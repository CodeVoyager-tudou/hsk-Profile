package com.chronic.shop.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chronic.common.result.Result;
import com.chronic.shop.entity.Coupon;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.service.AdminAuditService;
import com.chronic.shop.service.CouponService;
import com.chronic.shop.service.MedicineService;
import com.chronic.shop.vo.UserCouponVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 内部商城信息接口：供 AI 回答"这个药多少钱 / 用积分要多少分 / 我有哪些优惠券"。
 *
 * <h3>为什么要有这块</h3>
 * 商品价格必须来自商品表（{@code medicine.price} 现金价、{@code points_price} 积分价），
 * 而不是从用户的历史订单里倒推 —— 历史订单只能反映"当时买过什么价"，把它当现价回答会误导
 * （实测出现过："阿司匹林多少钱"被拿历史订单的 12 元当现价）。
 * 同一味药在本系统里有<b>两条渠道</b>：现金购买（¥）与积分兑换（积分），两者都要如实呈现。
 *
 * <p>与订单内部接口同样由 {@code X-Internal-Token} 保护（见 {@code InternalApiSecurityConfig}）。</p>
 *
 * @author chronic
 */
@Tag(name = "内部-商城信息")
@RestController
@RequestMapping("/internal/mall")
@RequiredArgsConstructor
public class InternalMallController {

    /** 单次最多返回多少条商品（AI 侧只用于展示候选，不做全量导出） */
    private static final int MAX_MEDICINE_ROWS = 20;

    private final MedicineService medicineService;
    private final CouponService couponService;
    private final AdminAuditService adminAuditService;

    @Operation(summary = "按关键词/分类查上架药品（内部）：关键词匹配药名、通用名或适应症")
    @GetMapping("/medicine/list")
    public Result<List<Medicine>> medicineList(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) Integer limit) {
        LambdaQueryWrapper<Medicine> wrapper = new LambdaQueryWrapper<Medicine>()
                .eq(Medicine::getStatus, 1);   // 只看上架商品
        if (category != null && !category.isBlank()) {
            wrapper.eq(Medicine::getCategory, category.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            // 药名 / 通用名 / 适应症 三处模糊匹配，交给 SQL（参数绑定）
            wrapper.and(w -> w.like(Medicine::getName, kw)
                    .or().like(Medicine::getGenericName, kw)
                    .or().like(Medicine::getIndication, kw));
        }
        wrapper.orderByAsc(Medicine::getId);
        // 用分页对象限制条数（不要用 last("LIMIT " + n) 那种拼 SQL 的写法）
        Page<Medicine> page = new Page<>(1,
                Math.min(Math.max(limit == null ? 5 : limit, 1), MAX_MEDICINE_ROWS));
        return Result.success(medicineService.page(page, wrapper).getRecords());
    }

    @Operation(summary = "按 id 查药品（内部）")
    @GetMapping("/medicine/{id}")
    public Result<Medicine> medicine(@PathVariable Long id) {
        return Result.success(medicineService.getById(id));
    }

    @Operation(summary = "查询用户优惠券（内部；status 可选 UNUSED/USED/EXPIRED）")
    @GetMapping("/coupon/list")
    public Result<List<UserCouponVO>> coupons(@RequestParam Long userId,
                                              @RequestParam(required = false) String status) {
        // 查的是"某个用户的券"，属于个人数据读取，AI 代查要留痕
        adminAuditService.record(userId, "AI_QUERY_COUPONS", "USER_ASSET", userId,
                "operator=AI(assistant), status=" + status);
        return Result.success(couponService.listMyCoupons(userId, status));
    }
}
