package com.chronic.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.points.entity.PointsRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 积分流水表 Mapper（MyBatis-Plus，无需编写 SQL）
 *
 * @author chronic
 */
@Mapper
public interface PointsRecordMapper extends BaseMapper<PointsRecord> {
}