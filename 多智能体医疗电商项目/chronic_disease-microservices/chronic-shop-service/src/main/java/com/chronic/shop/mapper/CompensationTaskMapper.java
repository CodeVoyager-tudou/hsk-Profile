package com.chronic.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.shop.entity.CompensationTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 补偿台账 Mapper；唯一键 (biz_type, biz_id) 保证同一笔业务失败只记一条，重试不会重复建账
 */
@Mapper
public interface CompensationTaskMapper extends BaseMapper<CompensationTask> {
}
