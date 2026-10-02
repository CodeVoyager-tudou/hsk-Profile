package com.chronic.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.user.entity.UserHealthProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户健康档案表 Mapper（MyBatis-Plus，无需编写 SQL）
 *
 * @author chronic
 */
@Mapper
public interface UserHealthProfileMapper extends BaseMapper<UserHealthProfile> {
}
