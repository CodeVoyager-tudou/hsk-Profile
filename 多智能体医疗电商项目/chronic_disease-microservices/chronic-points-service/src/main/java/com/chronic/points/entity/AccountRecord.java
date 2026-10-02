package com.chronic.points.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 余额流水实体，对应 account_record 表
 *
 * <p>{@code (type, source_id)} 唯一键是幂等护栏：BALANCE_PAY / BALANCE_REFUND 的
 * source_id 是订单 ID，同一订单同一类资金动作至多一条流水。</p>
 *
 * @author chronic
 */
@Data
@TableName("account_record")
public class AccountRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 流水类型：RECHARGE-充值 / BALANCE_PAY-余额支付 / BALANCE_REFUND-余额退款 */
    public static final String TYPE_RECHARGE = "RECHARGE";
    public static final String TYPE_BALANCE_PAY = "BALANCE_PAY";
    public static final String TYPE_BALANCE_REFUND = "BALANCE_REFUND";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 金额变动（正数入账，负数扣款） */
    private BigDecimal amount;

    private String type;

    private Long sourceId;

    private String remark;

    private LocalDateTime createTime;
}
