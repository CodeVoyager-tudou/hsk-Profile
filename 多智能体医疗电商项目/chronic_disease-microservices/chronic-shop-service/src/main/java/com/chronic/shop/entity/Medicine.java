package com.chronic.shop.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("medicine")
public class Medicine implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String genericName;

    private String category;

    private String indication;

    private String dosage;

    private BigDecimal price;

    private Integer stock;

    private Integer pointsReward;

    /** 积分兑换所需积分(null或0表示不支持积分兑换) */
    private Integer pointsPrice;

    private String imageUrl;

    private String manufacturer;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}