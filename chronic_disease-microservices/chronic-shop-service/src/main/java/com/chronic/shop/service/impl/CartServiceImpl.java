package com.chronic.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chronic.common.exception.BusinessException;
import com.chronic.shop.entity.CartItem;
import com.chronic.shop.entity.Medicine;
import com.chronic.shop.mapper.CartItemMapper;
import com.chronic.shop.mapper.MedicineMapper;
import com.chronic.shop.service.CartService;
import com.chronic.shop.vo.CartItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车服务：加购/列表/改数量/删除。
 *
 * <p>条目归属校验贯穿所有写操作—— itemId 是行主键，恶意用户可以直接调接口
 * 改/删别人的购物车条目（IDOR），所以每一步都带 user_id 条件或先比对归属。</p>
 *
 * <p>关于"秒杀商品能不能加购"：后端不做硬拦截。秒杀有独立的下单通道
 * （Redis 预扣 + MQ 异步，名额价格都与普通购买不同），但用户想按原价
 * 把它普通买下本身是合法需求，前端详情页对秒杀商品只隐藏加购按钮即可。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private static final int MAX_QUANTITY = 99;

    private final CartItemMapper cartItemMapper;
    private final MedicineMapper medicineMapper;

    @Override
    public void addItem(Long userId, Long medicineId, Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BusinessException("数量必须在 1~" + MAX_QUANTITY + " 之间");
        }
        Medicine medicine = medicineMapper.selectById(medicineId);
        if (medicine == null || medicine.getStatus() == 0) {
            throw new BusinessException("药品不存在或已下架");
        }
        // upsert：已有同药品条目时数量累加（数据库唯一键兜底并发），不需要先查后插
        cartItemMapper.upsertAdd(userId, medicineId, quantity);
        log.info("加入购物车: userId={}, medicineId={}, quantity={}", userId, medicineId, quantity);
    }

    @Override
    public List<CartItemVO> listCart(Long userId) {
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .orderByDesc(CartItem::getCreateTime));
        if (items.isEmpty()) {
            return List.of();
        }
        // 一次性批量取药品，不逐条查库
        List<Long> medicineIds = items.stream().map(CartItem::getMedicineId).distinct().toList();
        Map<Long, Medicine> byId = medicineMapper.selectBatchIds(medicineIds).stream()
                .collect(Collectors.toMap(Medicine::getId, Function.identity(), (a, b) -> a));
        return items.stream()
                .map(item -> CartItemVO.of(item, byId.get(item.getMedicineId())))
                .collect(Collectors.toList());
    }

    @Override
    public void updateQuantity(Long userId, Long itemId, Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BusinessException("数量必须在 1~" + MAX_QUANTITY + " 之间");
        }
        CartItem item = mustGetOwnedItem(userId, itemId);
        item.setQuantity(quantity);
        cartItemMapper.updateById(item);
    }

    @Override
    public void removeItem(Long userId, Long itemId) {
        mustGetOwnedItem(userId, itemId);
        cartItemMapper.deleteById(itemId);
    }

    @Override
    public void clearCart(Long userId) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>().eq(CartItem::getUserId, userId));
    }

    @Override
    public void deleteItems(Long userId, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }
        // 只删属于该用户的条目：结算清车复用本方法，用户传入的 ids 已经过归属校验，
        // 这里带上 userId 条件是最后一道防线（防止中途他人条目被误删）
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .in(CartItem::getId, itemIds));
    }

    /** 取条目并校验归属：不存在或不属于本人一律报"购物车条目不存在"，不泄露他人条目是否存在 */
    private CartItem mustGetOwnedItem(Long userId, Long itemId) {
        CartItem item = cartItemMapper.selectById(itemId);
        if (item == null || !item.getUserId().equals(userId)) {
            throw new BusinessException("购物车条目不存在");
        }
        return item;
    }
}
