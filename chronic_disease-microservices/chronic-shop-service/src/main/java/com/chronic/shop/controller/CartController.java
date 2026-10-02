package com.chronic.shop.controller;

import com.chronic.common.result.Result;
import com.chronic.shop.service.CartService;
import com.chronic.shop.vo.CartItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 购物车 Controller：加购、列表、改数量、删除。
 *
 * <p>用户身份一律取自网关鉴权后注入的 X-User-Id 请求头（JWT 解析结果），
 * 不接受前端传 userId 参数，杜绝越权（IDOR）——与 ShopOrderController 同一口径。</p>
 *
 * @author chronic
 */
@Tag(name = "购物车")
@RestController
@RequestMapping("/shop/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "加入购物车（同一药品重复加购自动合并数量，1~99）")
    @PostMapping("/items")
    public Result<Boolean> addItem(@RequestHeader("X-User-Id") Long userId,
                                   @RequestParam Long medicineId,
                                   @RequestParam Integer quantity) {
        cartService.addItem(userId, medicineId, quantity);
        return Result.success(true);
    }

    @Operation(summary = "我的购物车（带药品现价/图片/库存；下架或库存不足的条目 invalid=true）")
    @GetMapping
    public Result<List<CartItemVO>> listCart(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(cartService.listCart(userId));
    }

    @Operation(summary = "修改条目数量（1~99）")
    @PutMapping("/items/{itemId}")
    public Result<Boolean> updateQuantity(@RequestHeader("X-User-Id") Long userId,
                                          @PathVariable Long itemId,
                                          @RequestParam Integer quantity) {
        cartService.updateQuantity(userId, itemId, quantity);
        return Result.success(true);
    }

    @Operation(summary = "删除一条购物车条目")
    @DeleteMapping("/items/{itemId}")
    public Result<Boolean> removeItem(@RequestHeader("X-User-Id") Long userId,
                                      @PathVariable Long itemId) {
        cartService.removeItem(userId, itemId);
        return Result.success(true);
    }

    @Operation(summary = "清空购物车")
    @DeleteMapping
    public Result<Boolean> clearCart(@RequestHeader("X-User-Id") Long userId) {
        cartService.clearCart(userId);
        return Result.success(true);
    }
}
