package com.chronic.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chronic.user.audit.HealthDataAuditRecorder;
import com.chronic.user.client.AiConsultClient;
import com.chronic.user.entity.User;
import com.chronic.user.entity.UserHealthProfile;
import com.chronic.user.mapper.UserHealthProfileMapper;
import com.chronic.user.service.HealthAdviceService;
import com.chronic.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * AI 健康建议服务实现
 * <p>
 * 核心流程：读取健康档案 → 组装提问（身高体重/过敏史/病史/家族史）→ WebClient 调用
 * Python AI 服务。AI 不可用时降级为基于档案数据的通用建议，接口始终可用。
 * </p>
 *
 * @author chronic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthAdviceServiceImpl implements HealthAdviceService {

    private final UserService userService;
    private final UserHealthProfileMapper healthProfileMapper;
    private final AiConsultClient aiConsultClient;
    /** J-19：健康档案读取必须留审计（本接口会把档案内容传给 AI） */
    private final HealthDataAuditRecorder auditRecorder;

    @Override
    public String getAdvice(Long userId) {
        User user = userService.getById(userId);
        UserHealthProfile profile = healthProfileMapper.selectOne(
                new LambdaQueryWrapper<UserHealthProfile>().eq(UserHealthProfile::getUserId, userId));
        if (user == null || profile == null) {
            throw new com.chronic.common.exception.BusinessException("健康档案不存在，请先完善档案信息");
        }

        // J-19 修复：这里读取了健康档案，而且会把身高/体重/过敏史/既往病史/家族史
        // 拼进提问发给 AI —— 属于对敏感个人信息的访问，必须留审计记录。
        // 原先没有任何审计写入，一旦数据泄露无法回答"谁看了谁的档案"。
        auditRecorder.record(userId, userId, HealthDataAuditRecorder.ACTION_READ_PROFILE,
                "AI 健康建议（档案内容用于生成提问）");

        String query = buildQuery(user, profile);
        try {
            return aiConsultClient.query(query, "health-advice-" + userId, String.valueOf(userId));
        } catch (Exception e) {
            // 兜底：AI 服务超时/不可用时返回基于档案的通用建议，不让用户侧报错
            log.error("AI 健康建议调用失败，降级为通用建议, userId={}", userId, e);
            return fallbackAdvice(profile) + "\n\n---\n⚠️ AI 服务暂时不可用，以上为通用建议，请稍后重试获取个性化方案。";
        }
    }

    /**
     * 把健康档案拼成自然语言提问，供多智能体检索与专家作答
     */
    private String buildQuery(User user, UserHealthProfile profile) {
        StringBuilder sb = new StringBuilder("我的健康档案：");
        appendField(sb, "身高", profile.getHeight(), "cm");
        appendField(sb, "体重", profile.getWeight(), "kg");
        BigDecimal bmi = calcBmi(profile.getHeight(), profile.getWeight());
        if (bmi != null) {
            sb.append("，BMI ").append(bmi.setScale(1, RoundingMode.HALF_UP));
        }
        if (profile.getBloodType() != null && !profile.getBloodType().isEmpty()) {
            sb.append("，血型 ").append(profile.getBloodType());
        }
        appendField(sb, "过敏史", textOf(profile.getAllergies()), null);
        appendField(sb, "既往病史", textOf(profile.getMedicalHistory()), null);
        appendField(sb, "家族病史", textOf(profile.getFamilyHistory()), null);
        sb.append("。请结合以上情况，给出针对性的慢性病预防与管理建议（饮食、运动、需监测的指标）。");
        return sb.toString();
    }

    /**
     * 降级文案：AI 不可用时基于档案给通用建议（BMI 分级 + 过敏/病史提醒）
     */
    private String fallbackAdvice(UserHealthProfile profile) {
        StringBuilder sb = new StringBuilder("通用健康管理建议：\n");
        BigDecimal bmi = calcBmi(profile.getHeight(), profile.getWeight());
        if (bmi != null) {
            double v = bmi.doubleValue();
            if (v < 18.5) {
                sb.append("1. 体重偏轻（BMI ").append(String.format("%.1f", v))
                        .append("）：保证足量优质蛋白与热量摄入，配合适度力量训练。\n");
            } else if (v >= 24 && v < 28) {
                sb.append("1. 体重超重（BMI ").append(String.format("%.1f", v))
                        .append("）：控制总热量，每周≥150 分钟中等强度有氧运动。\n");
            } else if (v >= 28) {
                sb.append("1. 体重肥胖（BMI ").append(String.format("%.1f", v))
                        .append("）：建议在医生指导下制定减重计划，关注血压、血糖、血脂。\n");
            } else {
                sb.append("1. 体重正常（BMI ").append(String.format("%.1f", v))
                        .append("）：保持均衡饮食与规律运动即可。\n");
            }
        }
        String allergies = textOf(profile.getAllergies());
        if (!"无".equals(allergies) && !allergies.isEmpty()) {
            sb.append("2. 过敏史（").append(allergies)
                    .append("）：用药与饮食前主动告知医生，严格避开过敏原。\n");
        }
        String history = textOf(profile.getMedicalHistory());
        if (!"无".equals(history) && !history.isEmpty()) {
            sb.append("3. 既往病史（").append(history)
                    .append("）：遵医嘱规律复诊与用药，定期监测相关指标。\n");
        }
        sb.append("4. 低盐低油饮食，每日食盐<5g；戒烟限酒，保证 7-8 小时睡眠。");
        return sb.toString();
    }

    private BigDecimal calcBmi(BigDecimal heightCm, BigDecimal weightKg) {
        if (heightCm == null || weightKg == null
                || heightCm.compareTo(BigDecimal.ZERO) <= 0 || weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal heightM = heightCm.divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
        return weightKg.divide(heightM.multiply(heightM), 2, RoundingMode.HALF_UP);
    }

    private String textOf(String value) {
        return value == null || value.isBlank() ? "无" : value.trim();
    }

    private void appendField(StringBuilder sb, String label, Object value, String unit) {
        if (value == null || value.toString().isEmpty()) {
            return;
        }
        if (sb.charAt(sb.length() - 1) != '：') {
            sb.append("，");
        }
        sb.append(label).append(" ").append(value);
        if (unit != null) {
            sb.append(unit);
        }
    }
}