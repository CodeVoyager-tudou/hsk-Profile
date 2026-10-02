package com.chronic.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chronic.common.exception.BusinessException;
import com.chronic.shop.entity.Coupon;
import com.chronic.shop.entity.UserCoupon;
import com.chronic.shop.mapper.CouponMapper;
import com.chronic.shop.mapper.UserCouponMapper;
import com.chronic.shop.service.CouponService;
import com.chronic.shop.vo.UserCouponVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 优惠券服务实现，提供活动查询、领取、使用/退还
 * <p>
 * 领取环节用「分布式锁 + 业务唯一ID」双重防并发：
 * <ol>
 *   <li>Redisson RLock(key = chronic:lock:coupon:receive:{couponId}:{userId})把
 *       「查已领序号 -> 原子占名额 -> 建领取记录」串行化，加锁/解锁由 Lua 在 Redis 内原子完成；
 *       锁粒度是"单个用户 x 单个活动"，不影响其他人并发抢券</li>
 *   <li>占名额用 SQL 原子 UPDATE(issued_count &lt; total_count) 防超发</li>
 *   <li>数据库唯一键 uk_user_coupon_user_coupon_no(user_id, coupon_id, receive_no)兜底：
 *       多实例、锁过期、重试等极端情况下也只会有一条成功，其余转成业务提示，
 *       既不会超发也不会超领（Redis 不可用时也能靠它守住数据）</li>
 *   <li>整个领取在一个事务内：建记录失败会一并归还已占用的名额</li>
 * </ol>
 *
 * @author chronic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements CouponService {

    /** 领取锁 key 前缀，完整 key = 前缀 + couponId + ":" + userId */
    private static final String RECEIVE_LOCK_PREFIX = "chronic:lock:coupon:receive:";
    /** 获取锁最长等待时间(秒)：等不到说明同一用户同一活动还有请求在处理 */
    private static final long LOCK_WAIT_SECONDS = 3L;
    /** 锁自动释放时间(秒)：兜底防死锁，需大于一次领取事务耗时 */
    private static final long LOCK_LEASE_SECONDS = 10L;

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    /** Redis 未接入时为 empty，此时降级为仅依赖数据库唯一键兜底 */
    private final Optional<RedissonClient> redissonClient;

    @Override
    public List<Coupon> listActive() {
        // 查询当前时间在有效期内的所有启用优惠券，按抵扣金额降序排列
        LocalDateTime now = LocalDateTime.now();
        return list(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getStatus, 1)
                .le(Coupon::getStartTime, now)
                .ge(Coupon::getEndTime, now)
                .orderByDesc(Coupon::getDiscountAmount));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserCoupon receive(Long userId, Long couponId) {
        // 1. 校验优惠券活动是否存在
        Coupon coupon = getById(couponId);
        if (coupon == null) {
            throw new BusinessException("优惠券活动不存在");
        }
        // 2. 校验活动是否在有效期内
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getStatus() == null || coupon.getStatus() != 1
                || now.isBefore(coupon.getStartTime()) || now.isAfter(coupon.getEndTime())) {
            throw new BusinessException("活动不在有效期");
        }
        // 3. 加分布式锁：同一用户抢同一活动串行处理，避免"查已领 -> 再领取"的竞态
        RLock lock = lockReceive(userId, couponId);
        try {
            return doReceive(userId, coupon, now);
        } finally {
            // 4. 事务提交/回滚之后再释放锁：否则后一个请求可能读到前一个请求未提交的旧序号
            releaseAfterTransaction(lock);
        }
    }

    /**
     * 领取主体：必须在分布式锁内执行
     */
    private UserCoupon doReceive(Long userId, Coupon coupon, LocalDateTime now) {
        Long couponId = coupon.getId();
        int limitPerUser = Optional.ofNullable(coupon.getLimitPerUser()).orElse(1);
        // 已领序号最大值即"已领取次数"；>= 限领数直接拒绝
        int received = userCouponMapper.selectMaxReceiveNo(userId, couponId);
        if (received >= limitPerUser) {
            throw new BusinessException("已达每人限领上限");
        }
        int receiveNo = received + 1;
        // 原子占用量，防并发超发
        if (couponMapper.increaseIssuedCount(couponId) == 0) {
            throw new BusinessException("优惠券已被领完");
        }
        // 创建用户优惠券记录(携带业务唯一ID：receive_no)
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setCouponId(couponId);
        userCoupon.setUserId(userId);
        userCoupon.setReceiveNo(receiveNo);
        userCoupon.setStatus("UNUSED");
        userCoupon.setReceiveTime(now);
        userCoupon.setExpireTime(coupon.getEndTime());
        try {
            userCouponMapper.insert(userCoupon);
        } catch (DuplicateKeyException e) {
            // 唯一键兜底：极端并发/锁失效时同一序号只能插入一条，
            // 这里转成业务提示；抛异常会让事务回滚，第 4 步占用的名额一并归还
            log.warn("并发领取被唯一键拦截: userId={}, couponId={}, receiveNo={}", userId, couponId, receiveNo);
            throw new BusinessException("已达每人限领上限");
        }
        log.info("用户 {} 领取优惠券 {} 成功(该活动第 {} 张)", userId, couponId, receiveNo);
        return userCoupon;
    }

    /**
     * 获取领取锁；Redisson 不可用时返回 null 走降级(仅靠数据库唯一键兜底)
     */
    private RLock lockReceive(Long userId, Long couponId) {
        if (!redissonClient.isPresent()) {
            return null;
        }
        String key = RECEIVE_LOCK_PREFIX + couponId + ":" + userId;
        try {
            RLock lock = redissonClient.get().getLock(key);
            if (!lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException("领取请求处理中，请稍后重试");
            }
            return lock;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("领取请求被中断，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Redis 故障降级：不阻断领取(可用性优先)，数据由唯一键守住
            log.warn("优惠券分布式锁不可用，降级为仅依赖数据库唯一键: couponId={}, userId={}, 原因={}",
                    couponId, userId, e.getMessage());
            return null;
        }
    }

    /**
     * 事务提交后再释放锁；无事务时立即释放
     */
    private void releaseAfterTransaction(RLock lock) {
        if (lock == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    unlockQuietly(lock);
                }
            });
        } else {
            unlockQuietly(lock);
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            // 锁已因租约到期自动释放时，不越权释放别人持有的锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("释放优惠券分布式锁失败(锁会按租约自动过期): {}", e.getMessage());
        }
    }

    @Override
    public List<UserCouponVO> listMyCoupons(Long userId, String status) {
        // 按状态筛选用户优惠券，关联查询优惠券活动详情
        // 上限保护：券会不断累积，单次最多返回最近 200 张（已建索引 idx_user_receive_time）
        LambdaQueryWrapper<UserCoupon> wrapper = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getReceiveTime)
                .last("LIMIT 200");
        if (status != null && !status.isEmpty()) {
            wrapper.eq(UserCoupon::getStatus, status);
        }
        List<UserCoupon> userCoupons = userCouponMapper.selectList(wrapper);
        List<Long> couponIds = userCoupons.stream().map(UserCoupon::getCouponId).distinct().collect(Collectors.toList());
        Map<Long, Coupon> couponMap = couponIds.isEmpty()
                ? java.util.Collections.emptyMap()
                : listByIds(couponIds).stream().collect(Collectors.toMap(Coupon::getId, Function.identity()));
        List<UserCouponVO> result = new ArrayList<>();
        for (UserCoupon uc : userCoupons) {
            result.add(UserCouponVO.of(uc, couponMap.get(uc.getCouponId())));
        }
        return result;
    }

    @Override
    public void markUsed(Long userCouponId, Long userId, Long orderId) {
        // 原子核销优惠券，防止重复使用
        if (userCouponMapper.markUsed(userCouponId, userId, orderId) == 0) {
            throw new BusinessException("优惠券不可用（不存在、已使用或已过期）");
        }
    }

    @Override
    public void markUnused(Long userCouponId, Long orderId) {
        // 订单取消时把优惠券退回（J-06 修复）。
        //
        // 原实现只做 `WHERE id=? AND status='USED'`，比核销侧弱得多，可被套利：
        //   · 不校验有效期 -> 已过期的券被拉回 UNUSED，用户可在过期后继续当折扣用；
        //   · 不校验订单   -> 无法确认这张券确实是被本单用掉的。
        // 现在做与 markUsed 对称的校验：仅"本单使用且仍未过期"的券才退回为 UNUSED。
        int back = userCouponMapper.markUnused(userCouponId, orderId);
        if (back == 0) {
            // 未退回有两种可能：券不是本单用的，或券已过期。
            // 对"本单使用但已过期"的情况，置为 EXPIRED，避免留下
            // "USED 但已过期"的脏状态（前端会把它显示成已使用，与正常核销无法区分）。
            int expired = userCouponMapper.markExpiredIfUsedByOrder(userCouponId, orderId);
            if (expired > 0) {
                log.info("订单取消时优惠券已过期，置为 EXPIRED: userCouponId={}, orderId={}",
                        userCouponId, orderId);
            }
        }
    }
}
