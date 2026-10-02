package com.chronic.user.service;

/**
 * AI 健康建议服务：基于用户健康档案，经 WebClient 调用 Python 多智能体生成个性化建议
 *
 * @author chronic
 */
public interface HealthAdviceService {

    /**
     * 生成个性化健康建议
     *
     * @param userId 用户 ID（网关 AuthFilter 注入）
     * @return AI 建议文本；AI 服务不可用时降级为基于档案的通用建议
     */
    String getAdvice(Long userId);
}
