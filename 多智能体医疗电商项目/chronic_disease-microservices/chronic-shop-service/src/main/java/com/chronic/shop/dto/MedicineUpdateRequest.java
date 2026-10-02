package com.chronic.shop.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 编辑药品的请求体（管理端）。
 *
 * <p><b>字段全部可空，null 表示"这一列不改"</b>——管理端是对已有商品做局部修正，
 * 不该因为表单里没填的项把库里的值清空。字符串字段传空串是"显式清空"（如把适应症清掉）。
 * 哪些字段允许改由本类决定（白名单）：出价、名称、描述类字段可改，
 * 库存与上下架各自有独立接口（上下架走 {@code /status}），图片走 {@code /image}，
 * {@code id}/{@code create_time} 这类字段不接受客户端输入。</p>
 *
 * @author chronic
 */
@Data
public class MedicineUpdateRequest {

    /** 药品名称（非空；≤100） */
    private String name;

    /** 通用名（≤100） */
    private String genericName;

    /** 分类（≤50） */
    private String category;

    /** 生产厂家（≤200） */
    private String manufacturer;

    /** 适应症（≤2000，传空串=清空） */
    private String indication;

    /** 用法用量（≤2000，传空串=清空） */
    private String dosage;

    /** 现金价（> 0，最多两位小数） */
    private BigDecimal price;

    /** 库存绝对值（≥ 0，语义是"盘点校正"而非增减） */
    private Integer stock;

    /** 积分兑换价（≥ 0；0 表示不可兑换，与表注释一致） */
    private Integer pointsPrice;

    /** 现金购买返积分（≥ 0） */
    private Integer pointsReward;
}
