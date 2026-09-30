package com.chronic.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户余额账户实体，对应 user_account 表
 *
 * <p>余额是用户资产：扣减走 SQL 层原子条件更新（balance >= amount 才生效），
 * {@code version} 由 SQL 手动自增，用于排查与对账，不依赖注解式乐观锁拦截器。</p>
 *
 * @author chronic
 */
@Data
@TableName("user_account")
public class UserAccount implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 账户余额（元），与订单金额同为 DECIMAL(10,2)，全链路无浮点 */
    private BigDecimal balance;

    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
