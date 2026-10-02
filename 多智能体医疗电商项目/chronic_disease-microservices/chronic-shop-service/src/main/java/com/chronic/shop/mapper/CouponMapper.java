package com.chronic.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.shop.entity.Coupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CouponMapper extends BaseMapper<Coupon> {

    /**
     * 原子占用量:进行中且未发完才生效,防止超发
     */
    @Update("UPDATE coupon SET issued_count = issued_count + 1 "
            + "WHERE id = #{id} AND status = 1 AND issued_count < total_count")
    int increaseIssuedCount(@Param("id") Long id);
}
