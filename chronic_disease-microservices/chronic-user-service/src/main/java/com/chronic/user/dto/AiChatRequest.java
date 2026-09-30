package com.chronic.user.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.Serializable;

/**
 * AI 问答请求体（前端 -> user-service）
 *
 * @author chronic
 */
@Data
public class AiChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户提问 */
    @NotBlank(message = "提问内容不能为空")
    @Size(max = 2000, message = "提问内容过长，请控制在 2000 字以内")
    private String query;

    /** 会话 ID（多轮对话），为空则服务端生成 */
    private String sessionId;

    /** 知识源筛选（disease/medication/lifestyle/lab/risk），可空 */
    private String sourceFilter;
}
