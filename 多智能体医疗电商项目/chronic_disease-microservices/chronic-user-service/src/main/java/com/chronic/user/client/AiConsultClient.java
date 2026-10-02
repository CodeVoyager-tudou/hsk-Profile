package com.chronic.user.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Python AI 服务客户端（Spring WebClient）
 * <p>
 * 调用链：user-service --WebClient--> nginx 负载均衡(9000) --> app.py 多实例(8001/8002)。
 * Python 不直接面向前端：非流式走 /api/query，流式走 /api/query/stream（JSON Lines），
 * 由本服务转成 SSE 提供给前端。连接与响应超时可配置；调用失败由上层捕获后降级，
 * 不让 AI 故障拖垮用户服务。
 * </p>
 *
 * @author chronic
 */
@Slf4j
@Component
public class AiConsultClient {

    /** Python /api/query 响应体 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    // 忽略未知字段，防止 JSON 反序列化失败
    public static class AiQueryResponse {
        private String answer; // 回答文本
        private Boolean isStreaming; // 是否流式返回
        private String sessionId; // 会话 ID
        private Double processingTime; // 处理耗时（秒）
    }

    // 客户端配置：响应式 HTTP 客户端，用于调用 Python AI 服务
    private final WebClient webClient;
    // 响应超时配置：单次请求的最大等待时间（秒），超时后抛出 TimeoutException
    private final long responseTimeoutSeconds;

    /**
     * 初始化 AI 客户端，配置连接超时、响应超时、基础 URL
     *
     * @param baseUrl               Python AI 服务的基础地址（含 nginx 端口），默认 http://localhost:9000
     * @param connectTimeoutMs      建立 TCP 连接的超时时间（毫秒），默认 2000ms
     * @param responseTimeoutSeconds 等待响应的超时时间（秒），默认 60s
     */
    public AiConsultClient(
            @Value("${chronic.ai.base-url:http://localhost:9000}") String baseUrl,
            @Value("${chronic.ai.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${chronic.ai.response-timeout-seconds:60}") long responseTimeoutSeconds,
            @Value("${chronic.ai.internal-token:}") String internalToken) {
        this.responseTimeoutSeconds = responseTimeoutSeconds;
        // 配置底层 Netty HTTP 客户端：连接超时 + 响应超时
        HttpClient httpClient = HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeoutMs)
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds));
        // 构建 WebClient 实例，绑定基础 URL 和自定义 HTTP 客户端
        // Python 侧开启内部令牌后（AI_INTERNAL_TOKEN），不带该头会被 403
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (internalToken != null && !internalToken.trim().isEmpty()) {
            builder.defaultHeader("X-Internal-Token", internalToken.trim());
        }
        this.webClient = builder.build();
    }

    /**
     * 非流式问答：POST /api/query（nginx 负载均衡到 Python 实例）
     *
     * @param query     用户输入的提问文本
     * @param sessionId 会话 ID，用于多轮对话上下文关联，可空
     * @param userId    归属用户（会话管理/权限），可空
     * @return AI 回答文本；超时/异常向上抛出，由调用方兜底
     */
    public String query(String query, String sessionId, String userId) {
        // 构建请求体并发送 POST 请求到 Python AI 服务
        AiQueryResponse response = webClient.post()
                .uri("/api/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildBody(query, sessionId, null, userId))
                .retrieve()
                // 将响应体反序列化为 AiQueryResponse 对象
                .bodyToMono(AiQueryResponse.class)
                // 额外超时兜底：比全局配置多 5 秒，防止 AI 计算耗时接近阈值时被误杀
                .timeout(Duration.ofSeconds(responseTimeoutSeconds + 5))
                .block();
        if (response == null || response.getAnswer() == null) {
            throw new IllegalStateException("AI 服务返回为空");
        }
        return response.getAnswer();
    }

    /**
     * 流式问答：POST /api/query/stream，Python 返回 JSON Lines（每行一个 JSON 事件，
     * 内容无裸换行，可安全按 \n 分行）。手动按 DataBuffer 累积分行，不依赖编解码器
     * 对媒体类型的支持差异，逐行产出原始 JSON 字符串（{"type":"token"|"end"|"error",...}）
     *
     * @param query        用户输入的提问文本
     * @param sessionId    会话 ID，用于多轮对话上下文关联，可空
     * @param sourceFilter 知识源过滤条件，可空表示不限制知识源
     * @param userId       归属用户 ID，可空
     * @return 逐行 JSON 字符串的响应式流，每行一个事件
     */
    public Flux<String> streamLines(String query, String sessionId, String sourceFilter, String userId) {
        return Flux.defer(() -> {
            // 每次订阅独立的跨 chunk 缓冲：最后一个不带换行的半行留到下一个 chunk 拼接
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            return webClient.post()
                    .uri("/api/query/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(buildBody(query, sessionId, sourceFilter, userId))
                    .retrieve()
                    // 以 DataBuffer 形式逐块接收响应体，避免一次性加载到内存
                    .bodyToFlux(DataBuffer.class)
                    .concatMap(buffer -> {
                        // 从 DataBuffer 中读取字节数据
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        // 释放 Netty 缓冲区，防止内存泄漏
                        DataBufferUtils.release(buffer);
                        // 将新数据追加到待处理缓冲区
                        pending.write(bytes, 0, bytes.length);
                        // 按换行符切分完整行并逐行产出
                        return Flux.fromIterable(splitLines(pending));
                    })
                    // 事件间隔超时：Python 卡死但连接未断时，避免用户侧 SSE 无限挂起
                    .timeout(Duration.ofSeconds(responseTimeoutSeconds),
                            Flux.error(new IllegalStateException("AI 流式响应超时")));
        });
    }

    /**
     * 知识源列表：GET /api/sources
     *
     * @return 可用知识源的名称列表，调用失败时返回空列表
     */
    public List<String> fetchSources() {
        // 发送 GET 请求获取知识源信息
        Map<String, Object> response = webClient.get()
                .uri("/api/sources")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .block();
        // 提取 sources 字段，类型安全校验
        Object sources = response == null ? null : response.get("sources");
        if (!(sources instanceof List)) {
            return Collections.emptyList();
        }
        // 逐元素转 String，避免列表元素类型异常时在使用处才抛 ClassCastException
        return ((List<?>) sources).stream().map(String::valueOf).collect(java.util.stream.Collectors.toList());
    }

    // ===== 会话管理代理（历史会话/标题/回看），Python 持久化在 PostgreSQL =====
    // 注意：sessionId 一律经 URI 模板变量传递（WebClient 自动编码），
    // 禁止手工字符串拼接（sessionId 含 / ? 空格时会篡改请求路径）

    /**
     * 创建会话：POST /api/sessions
     *
     * @param userId 归属用户 ID，可空（匿名会话）
     * @param title  会话标题，可空则默认为空字符串
     * @return Python 返回的会话信息 Map（含 session_id 等字段）
     */
    public Map<String, Object> createSession(String userId, String title) {
        Map<String, Object> body = new HashMap<>();
        if (userId != null && !userId.isEmpty()) {
            body.put("user_id", userId);
        }
        body.put("title", title == null ? "" : title);
        return blockPost("/api/sessions", body);
    }

    /**
     * 历史会话列表：GET /api/sessions?user_id=
     *
     * @param userId 归属用户 ID
     * @return 该用户下所有历史会话的列表
     */
    public Map<String, Object> listSessions(String userId) {
        return blockGet("/api/sessions", null, "user_id", userId);
    }

    /**
     * 会话消息回看：GET /api/sessions/{sid}/messages?user_id=
     *
     * @param sessionId 会话 ID
     * @param userId    归属用户 ID，用于权限校验
     * @return 该会话下的所有消息记录
     */
    public Map<String, Object> sessionMessages(String sessionId, String userId) {
        return blockGet("/api/sessions/{sid}/messages", sessionId, "user_id", userId);
    }

    /**
     * 重命名会话：PATCH /api/sessions/{sid}
     *
     * @param sessionId 会话 ID
     * @param title     新标题
     * @param userId    归属用户 ID，用于权限校验
     * @return 更新后的会话信息
     */
    public Map<String, Object> renameSession(String sessionId, String title, String userId) {
        Map<String, Object> body = new HashMap<>();
        if (userId != null && !userId.isEmpty()) {
            body.put("user_id", userId);
        }
        body.put("title", title);
        // 使用 URI 模板方式传递 sessionId，WebClient 自动编码，防止路径注入
        return webClient.patch()
                .uri(builder -> builder.path("/api/sessions/{sid}").build(sessionId))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .block();
    }

    /**
     * 删除会话：DELETE /api/sessions/{sid}?user_id=
     *
     * @param sessionId 会话 ID
     * @param userId    归属用户 ID，用于权限校验
     * @return 删除结果
     */
    public Map<String, Object> deleteSession(String sessionId, String userId) {
        return blockDelete("/api/sessions/{sid}", sessionId, "user_id", userId);
    }

    /**
     * 通用 POST 请求封装，超时 10 秒
     *
     * @param uri  请求路径
     * @param body 请求体
     * @return 响应体 Map，失败时返回空 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> blockPost(String uri, Map<String, Object> body) {
        Map<String, Object> resp = webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .block();
        return resp == null ? Collections.emptyMap() : resp;
    }

    /**
     * 通用 GET 请求封装，path 用 URI 模板（自动编码变量），query 参数用 builder 附加（自动编码），超时 10 秒
     *
     * @param pathTemplate URI 路径模板，如 /api/sessions/{sid}/messages
     * @param uriVar       路径变量值，无变量时传 null
     * @param param        query 参数名
     * @param value        query 参数值，为空时不追加该参数
     * @return 响应体 Map，失败时返回空 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> blockGet(String pathTemplate, Object uriVar, String param, String value) {
        Map<String, Object> resp = webClient.get()
                .uri(b -> {
                    b.path(pathTemplate);
                    // 仅在 value 不为空时追加 query 参数，避免向后端传递空字符串
                    if (value != null && !value.isEmpty()) {
                        b.queryParam(param, value);
                    }
                    return uriVar == null ? b.build() : b.build(uriVar);
                })
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .block();
        return resp == null ? Collections.emptyMap() : resp;
    }

    /**
     * 通用 DELETE 请求封装，URI 模板 + query 参数自动编码，超时 10 秒
     *
     * @param pathTemplate URI 路径模板，如 /api/sessions/{sid}
     * @param uriVar       路径变量值，无变量时传 null
     * @param param        query 参数名
     * @param value        query 参数值，为空时不追加该参数
     * @return 响应体 Map，失败时返回空 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> blockDelete(String pathTemplate, Object uriVar, String param, String value) {
        Map<String, Object> resp = webClient.delete()
                .uri(b -> {
                    b.path(pathTemplate);
                    if (value != null && !value.isEmpty()) {
                        b.queryParam(param, value);
                    }
                    return b.build(uriVar);
                })
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .block();
        return resp == null ? Collections.emptyMap() : resp;
    }

    /**
     * 从累积缓冲中按换行符切分完整行，未以换行结尾的半行保留在缓冲中等待下一批数据
     * <p>
     * 流式场景下 TCP 分包可能将一行 JSON 切分到多个 DataBuffer 中，
     * 此方法保证每次只产出完整行，避免 JSON 解析失败。
     * </p>
     *
     * @param pending 累积缓冲区（入参含所有已接收数据，出参仅保留未完成的半行）
     * @return 本次可产出的完整行列表（已去除首尾空白，已过滤空行）
     */
    private List<String> splitLines(ByteArrayOutputStream pending) {
        byte[] all = pending.toByteArray();
        List<String> lines = new ArrayList<>();
        int start = 0;
        // 遍历字节数组，按 \n 切分
        for (int i = 0; i < all.length; i++) {
            if (all[i] == '\n') {
                // 截取一行内容，去掉首尾空白字符
                String line = new String(all, start, i - start, StandardCharsets.UTF_8).trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
                start = i + 1;
            }
        }
        // 清空缓冲区，将未完成的半行（start 之后的内容）重新写入
        pending.reset();
        if (start < all.length) {
            pending.write(all, start, all.length - start);
        }
        return lines;
    }

    /**
     * 构建请求体 Map，统一处理可选字段的空值判断
     *
     * @param query        提问文本（必填）
     * @param sessionId    会话 ID，可空
     * @param sourceFilter 知识源过滤条件，可空时不传递该字段
     * @param userId       归属用户 ID，可空时不传递该字段
     * @return 请求体 Map
     */
    private Map<String, Object> buildBody(String query, String sessionId, String sourceFilter, String userId) {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("session_id", sessionId);
        // 仅在有值时传递可选字段，避免 Python 端收到空字符串
        if (sourceFilter != null && !sourceFilter.isEmpty()) {
            body.put("source_filter", sourceFilter);
        }
        if (userId != null && !userId.isEmpty()) {
            body.put("user_id", userId);
        }
        return body;
    }
}