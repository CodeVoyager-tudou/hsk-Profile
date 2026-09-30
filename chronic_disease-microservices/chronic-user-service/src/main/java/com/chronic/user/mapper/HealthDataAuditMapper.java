package com.chronic.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chronic.user.entity.HealthDataAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 健康数据访问审计 Mapper（J-19 修复：补齐此前"表建了但零写入"的缺口）。
 *
 * <p>写入路径见 {@link com.chronic.user.audit.HealthDataAuditRecorder}。
 * 查询按 {@code (target_user_id, create_time)} 与 {@code (operator_id, create_time)}
 * 两个索引走（已在 V1 建好），可支撑"某人看了谁的数据"与"谁看了某人的数据"两个方向。</p>
 *
 * @author chronic
 */
@Mapper
public interface HealthDataAuditMapper extends BaseMapper<HealthDataAudit> {
}
