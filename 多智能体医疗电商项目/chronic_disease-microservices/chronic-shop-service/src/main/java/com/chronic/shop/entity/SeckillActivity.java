package com.chronic.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动实体，对应 seckill_activity 表。
 *
 * <p>本表是秒杀的<b>权威库存</b>（total_stock / sold_count）；Redis 只是预扣闸门。
 * 一人一单由三层保证：Lua SISMEMBER（挡请求）→ 订单 requestId 唯一键
 * （SK-{activityId}-{userId}-{本次抢购随机后缀}，兜底 MQ 重投幂等）→ 消费者建单前的重复检查。
 * 后缀是必需的：秒杀单超时关单会释放名额（用户可再抢），固定键会让第二次抢购撞上已取消的旧单。</p>
 *
 * @author chronic
 */
@Data
@TableName("seckill_activity")
public class SeckillActivity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long medicineId;

    private String title;

    private BigDecimal seckillPrice;

    private Integer totalStock;

    private Integer soldCount;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 1-上架 0-下架 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 药品名（非表字段）：列表接口按 medicineId 回填。
     * 秒杀卡片要显示"这个药自己的图片"，而活动本身只有标题，
     * 前端拿药品名才能与商城列表取到同一张图。
     */
    @TableField(exist = false)
    private String medicineName;

    /** 药品图片 URL（非表字段，同上；为空时前端退回按药品名生成的占位图） */
    @TableField(exist = false)
    private String medicineImageUrl;
}
