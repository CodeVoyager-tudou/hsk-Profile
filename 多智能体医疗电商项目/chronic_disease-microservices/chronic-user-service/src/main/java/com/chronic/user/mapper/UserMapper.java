package com.chronic.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户表 Mapper（MyBatis-Plus，无需编写 SQL）
 *
 * @author chronic
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 只更新头像一列（窄更新）。
     * <p>不整对象 updateById：并发下会把内存快照里的其他旧字段一起写回去
     * （shop 的订单退款 J-05 就是这个坑），更新要窄、条件要带主键。</p>
     */
    @Update("UPDATE sys_user SET avatar = #{url} WHERE id = #{userId}")
    int updateAvatar(@Param("userId") Long userId, @Param("url") String url);
}