package com.chronic.points.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.points.entity.SignInRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 签到记录表 Mapper（MyBatis-Plus，无需编写 SQL）
 *
 * @author chronic
 */
@Mapper
public interface SignInRecordMapper extends BaseMapper<SignInRecord> {
}