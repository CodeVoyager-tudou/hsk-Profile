package com.chronic.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.shop.entity.AdminAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理审计 Mapper（append-only，只用到 insert 与 selectPage）
 *
 * @author chronic
 */
@Mapper
public interface AdminAuditMapper extends BaseMapper<AdminAudit> {
}
