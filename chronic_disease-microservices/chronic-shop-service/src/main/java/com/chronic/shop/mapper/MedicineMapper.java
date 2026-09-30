package com.chronic.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.shop.entity.Medicine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MedicineMapper extends BaseMapper<Medicine> {

    /**
     * 原子扣库存:上架且库存充足才生效,防并发超卖
     */
    @Update("UPDATE medicine SET stock = stock - #{quantity} "
            + "WHERE id = #{id} AND status = 1 AND stock >= #{quantity}")
    int reduceStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 订单取消回补库存
     */
    @Update("UPDATE medicine SET stock = stock + #{quantity} WHERE id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 只更新图片地址一列（窄更新，并发下不会把快照旧值写回其他列）。
     * 影响行数为 0 说明药品不存在（或已下架被删），由调用方判定。
     */
    @Update("UPDATE medicine SET image_url = #{url} WHERE id = #{id}")
    int updateImageUrl(@Param("id") Long id, @Param("url") String url);

    /**
     * 上下架（管理端）：status 只允许 0/1，由调用方校验
     */
    @Update("UPDATE medicine SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 编辑药品（管理端，窄更新）：只写 patch 里非 null 的列，null 表示"这一列不改"。
     *
     * <p>库存是管理员设定的绝对值（不是增减），语义为"盘点校正"；
     * 上下架与图片各自有独立窄更新（{@link #updateStatus} / {@link #updateImageUrl}），
     * 不在这里顺手一起改——避免一次"改个描述"的提交意外把商品下架。</p>
     *
     * <p>{@code <set>} 会自动去掉最后多出来的逗号；末尾保留 {@code id = id} 兜底，
     * 使 patch 全空时也不会拼出 {@code UPDATE medicine WHERE ...} 这种非法 SQL
     * （调用方已校验过非空，这里是纵深防御）。</p>
     *
     * <p><b>隐含依赖（复核 P2-11，勿破坏）：</b>调用方用返回值 0 判定"药品不存在"抛 404，
     * 这依赖驱动的 {@code useAffectedRows=false}（Connector/J 默认值，返回<b>匹配行数</b>
     * 而非变更行数）—— 行存在就返回 ≥1，全空 patch / 同值 patch 才不会误报 404。
     * 三个服务的 JDBC URL 已显式写明该参数；若有人改回 {@code true}（变更行数语义），
     * 值没变的 patch 会返回 0 → 误报 404。编译期拦不住，只能靠这里与 URL 双处写明。</p>
     */
    @Update("<script>UPDATE medicine <set>"
            + "<if test='m.name != null'>name = #{m.name}, </if>"
            + "<if test='m.genericName != null'>generic_name = #{m.genericName}, </if>"
            + "<if test='m.category != null'>category = #{m.category}, </if>"
            + "<if test='m.manufacturer != null'>manufacturer = #{m.manufacturer}, </if>"
            + "<if test='m.indication != null'>indication = #{m.indication}, </if>"
            + "<if test='m.dosage != null'>dosage = #{m.dosage}, </if>"
            + "<if test='m.price != null'>price = #{m.price}, </if>"
            + "<if test='m.stock != null'>stock = #{m.stock}, </if>"
            + "<if test='m.pointsPrice != null'>points_price = #{m.pointsPrice}, </if>"
            + "<if test='m.pointsReward != null'>points_reward = #{m.pointsReward}, </if>"
            + "id = id</set> WHERE id = #{id}</script>")
    int updateEditable(@Param("id") Long id, @Param("m") Medicine patch);
}
