package com.chronic.user.controller;

import com.chronic.common.result.Result;
import com.chronic.user.client.AiConsultClient;
import com.chronic.user.dto.AiChatRequest;
import com.chronic.user.dto.SessionTitleRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * AI 助手代理 Controller（Java 与前端的交互层）
 * <p>
 * 前端不直连 Python：本控制器经 WebClient 从 Python 拉取 JSON Lines token 流，
 * 转成 SSE（text/event-stream）推给前端。Python 故障时以 error 事件兜底，不断流给前端。
 * 网关路由 /api/ai/** 重写到 /user/ai/**。
 * </p>
 *
 * @author chronic
 */
@Slf4j
@Tag(name = "AI 助手代理")
@RestController
@RequestMapping("/user/ai")
public class AiProxyController {

    private final AiConsultClient aiConsultClient;
    private final ObjectMapper objectMapper;

    /**
     * AI 流式并发闸门（J-18 修复）。
     *
     * <p>配置项 {@code chronic.ai.max-concurrent-requests}（application.yml 里为 8）
     * 原先**没有任何代码读取它**，是一条死配置；同时 {@link #queryStream} 也没有任何
     * 并发保护。而网关的限流是按 <b>QPS</b> 计的（10 QPS），
     * QPS 与并发是两件事：一个流式请求可能持续 60 秒，
     * 于是 10 QPS 在理论上能堆出数百个并发的长连接，
     * 把本机模型/显存打满并引发级联超时。</p>
     *
     * <p>这里用信号量把并发限制在配置值以内，超出即快速失败
     * （返回一个 error 事件而不是排队等待），避免拖垮后端。</p>
     */
    private final Semaphore streamPermits;
    private final int maxConcurrentStreams;

    public AiProxyController(AiConsultClient aiConsultClient,
                             ObjectMapper objectMapper,
                             @Value("${chronic.ai.max-concurrent-requests:8}") int maxConcurrentRequests) {
        this.aiConsultClient = aiConsultClient;
        this.objectMapper = objectMapper;
        this.maxConcurrentStreams = Math.max(1, maxConcurrentRequests);
        this.streamPermits = new Semaphore(this.maxConcurrentStreams, true);
    }

    @Operation(summary = "AI 问答（非流式，WebClient 调 Python）")
    @PostMapping("/query")
    public Result<String> query(@Valid @RequestBody AiChatRequest request,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        String sessionId = resolveSessionId(request, userId);
        try {
            return Result.success(aiConsultClient.query(request.getQuery(), sessionId, userIdStr(userId)));
        } catch (Exception e) {
            // 非流式与流式路径保持一致的降级语义：AI 故障不冒泡成系统 500
            log.error("AI 问答失败, sessionId={}", sessionId, e);
            return Result.error("AI 服务暂时不可用，请稍后重试");
        }
    }

    @Operation(summary = "AI 问答（SSE 流式：WebClient 拉取 Python JSONL 流，转发为 SSE）")
    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> queryStream(@RequestBody AiChatRequest request,
                                                     @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        String sessionId = resolveSessionId(request, userId);

        // 并发闸门（J-18）：拿不到许可立即快速失败，不排队 ——
        // 排队只会让请求越积越多，最终一起超时。
        if (!streamPermits.tryAcquire()) {
            log.warn("AI 流式并发已达上限 {}，拒绝本次请求: sessionId={}", maxConcurrentStreams, sessionId);
            return Flux.just(sse("error",
                    "{\"type\":\"error\",\"message\":\"当前 AI 请求过多，请稍后重试\"}"));
        }

        return aiConsultClient.streamLines(request.getQuery(), sessionId, request.getSourceFilter(), userIdStr(userId))
                .mapNotNull(this::toSse)
                .onErrorResume(e -> {
                    // 兜底：Python 不可达/超时时向前端下发 error 事件，保证连接有序结束
                    log.error("AI 流式转发失败, sessionId={}", sessionId, e);
                    return Flux.just(sse("error",
                            "{\"type\":\"error\",\"message\":\"AI 服务暂时不可用，请稍后重试\"}"));
                })
                // 必须在**所有**终止路径上归还许可：正常完成、异常、以及客户端提前断开
                // （浏览器关闭 / 前端 abort）。漏掉任何一条都会让许可永久泄漏，
                // 最终信号量归零、服务再也无法处理任何 AI 请求。
                .doFinally(signal -> streamPermits.release());
    }

    @Operation(summary = "知识源列表（代理 Python /api/sources）")
    @GetMapping("/sources")
    public Result<List<String>> sources() {
        return Result.success(aiConsultClient.fetchSources());
    }

    // ===== 会话管理（历史会话/自定义标题，需登录：user_id 取自网关注入的 X-User-Id） =====

    @Operation(summary = "创建会话")
    @PostMapping("/sessions")
    public Result<Map<String, Object>> createSession(@RequestBody(required = false) SessionTitleRequest body,
                                                     @RequestHeader("X-User-Id") Long userId) {
        String title = body == null ? "" : body.getTitle();
        return Result.success(aiConsultClient.createSession(userIdStr(userId), title));
    }

    @Operation(summary = "历史会话列表")
    @GetMapping("/sessions")
    public Result<Map<String, Object>> listSessions(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(aiConsultClient.listSessions(userIdStr(userId)));
    }

    @Operation(summary = "会话消息回看")
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<Map<String, Object>> sessionMessages(@PathVariable String sessionId,
                                                       @RequestHeader("X-User-Id") Long userId) {
        return Result.success(aiConsultClient.sessionMessages(sessionId, userIdStr(userId)));
    }

    @Operation(summary = "重命名会话（自定义标题）")
    @PatchMapping("/sessions/{sessionId}")
    public Result<Map<String, Object>> renameSession(@PathVariable String sessionId,
                                                     @RequestBody SessionTitleRequest body,
                                                     @RequestHeader("X-User-Id") Long userId) {
        return Result.success(aiConsultClient.renameSession(sessionId, body.getTitle(), userIdStr(userId)));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Map<String, Object>> deleteSession(@PathVariable String sessionId,
                                                     @RequestHeader("X-User-Id") Long userId) {
        return Result.success(aiConsultClient.deleteSession(sessionId, userIdStr(userId)));
    }

    /**
     * 把 Python JSONL 行转成 SSE 事件：event 名取 JSON 的 type 字段，data 原样透传
     */
    private ServerSentEvent<String> toSse(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.path("type").asText("token");
            return sse(type, line);
        } catch (Exception e) {
            log.warn("AI 流事件解析失败, 忽略该行: {}", line, e);
            return null;
        }
    }

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    private String resolveSessionId(AiChatRequest request, Long userId) {
        if (StringUtils.hasText(request.getSessionId())) {
            return request.getSessionId();
        }
        return "ai-" + (userId == null ? "anon" : userId) + "-" + UUID.randomUUID();
    }

    private static String userIdStr(Long userId) {
        return userId == null ? null : String.valueOf(userId);
    }
}
