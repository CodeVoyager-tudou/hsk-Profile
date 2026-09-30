package com.chronic.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.points.entity.AccountRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 余额流水 Mapper（插入走 BaseMapper；(type, source_id) 唯一键提供幂等护栏）
 *
 * @author chronic
 */
@Mapper
public interface AccountRecordMapper extends BaseMapper<AccountRecord> {

    /**
     * 最近 1 分钟内的充值次数（充值限频用）。
     * 用流水表计数而不是 Redis：points 服务不依赖 Redis，避免为一项限频引入新组件。
     */
    @Select("SELECT COUNT(*) FROM account_record "
            + "WHERE user_id = #{userId} AND type = 'RECHARGE' "
            + "AND create_time > DATE_SUB(NOW(), INTERVAL 1 MINUTE)")
    int countRecentRecharges(@Param("userId") Long userId);
}
