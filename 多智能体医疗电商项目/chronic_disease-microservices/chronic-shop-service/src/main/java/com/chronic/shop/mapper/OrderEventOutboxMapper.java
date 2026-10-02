package com.chronic.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.shop.entity.OrderEventOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单事件本地消息表 Mapper（J-14）。
 *
 * <p>唯一键 {@code (order_id, event_type)} 保证同一订单的同一类事件只记一条，
 * 因此重复调用「记录事件」不会产生重复投递。</p>
 *
 * <p><b>为什么状态变更都用显式 SQL 而不是 LambdaUpdateWrapper：</b>
 * MyBatis-Plus 的 Lambda 写法依赖运行时构建的实体元数据缓存
 * （{@code TableInfoHelper} 在容器启动时初始化）。这有两个坏处：
 * 一是纯单元测试里会直接抛 {@code can not find lambda cache}，
 * 二是 SQL 被藏在 Lambda 表达式后面、看代码时不容易一眼看出会更新哪些列。
 * 显式 SQL 两者都能避免。</p>
 */
@Mapper
public interface OrderEventOutboxMapper extends BaseMapper<OrderEventOutbox> {

    /**
     * 取一批「到时间该重试」的待发送事件。
     * <p>按 id 升序保证大致先进先出；LIMIT 防止一次拉太多。</p>
     */
    @Select("SELECT * FROM order_event_outbox "
            + "WHERE status = 'PENDING' AND (next_retry_time IS NULL OR next_retry_time <= NOW()) "
            + "ORDER BY id LIMIT #{limit}")
    List<OrderEventOutbox> selectPendingBatch(@Param("limit") int limit);

    /**
     * 标记发送成功。
     * <p>条件 {@code status = 'PENDING'} 是幂等护栏：即使两个中继实例同时处理同一条，
     * 也只有一个能把状态改成 SENT，避免"重复发送"被记录成两次成功。</p>
     */
    @Update("UPDATE order_event_outbox SET status = 'SENT', sent_time = NOW(), last_error = NULL "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markSent(@Param("id") Long id);

    /** 记录一次失败并安排下次重试（仍保持 PENDING，等待中继再捞） */
    @Update("UPDATE order_event_outbox SET retry_count = #{retryCount}, last_error = #{lastError}, "
            + "next_retry_time = #{nextRetryTime} WHERE id = #{id} AND status = 'PENDING'")
    int markRetry(@Param("id") Long id,
                  @Param("retryCount") int retryCount,
                  @Param("lastError") String lastError,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime);

    /** 超过重试上限：转 FAILED，等人工介入（不再自动重试） */
    @Update("UPDATE order_event_outbox SET status = 'FAILED', last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);
}
