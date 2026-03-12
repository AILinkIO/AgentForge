package io.ailink.agentforge.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.llm.dto.ChatRequest;
import io.ailink.agentforge.llm.dto.ChatResponse;
import org.slf4j.Logger;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM 提供者抽象基类
 *
 * 封装通用的 HTTP 调用逻辑和请求/响应转换。
 * 子类只需实现特定 Provider 的转换逻辑。
 *
 * 设计模式：Template Method
 * - chatAsync() 和 chatStream() 提供模板逻辑
 * - 子类实现 convertRequest(), convertResponse() 等钩子方法
 *
 * @param <P> Provider 特定的请求类型
 * @param <R> Provider 特定的响应类型
 */
public abstract class AbstractLlmProvider<P, R> implements LlmProvider {

    /**
     * WebClient 实例，用于发送 HTTP 请求
     */
    protected final WebClient webClient;

    /**
     * JSON 序列化/反序列化工具
     */
    protected final ObjectMapper objectMapper;

    /**
     * 日志记录器
     */
    protected final Logger log;

    protected AbstractLlmProvider(WebClient webClient, ObjectMapper objectMapper, Logger log) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.log = log;
    }

    /**
     * 同步聊天
     *
     * 模板方法：委托给异步调用并阻塞等待结果
     */
    @Override
    public ChatResponse<?> chat(ChatRequest request) {
        return chatAsync(request).block();
    }

    /**
     * 异步聊天
     *
     * 模板方法：
     * 1. 构建 Provider 特定请求
     * 2. 发送请求
     * 3. 转换为通用响应
     * 4. 错误处理
     */
    @Override
    public final Mono<? extends ChatResponse<?>> chatAsync(ChatRequest request) {
        P providerRequest = convertRequest(request, false);

        return executeRequest(providerRequest)
                .map(this::convertResponse)
                .onErrorResume(error -> {
                    log.error("LLM 请求失败: {}", error.getMessage());
                    return Mono.error(error);
                });
    }

    /**
     * 流式聊天
     *
     * 模板方法：
     * 1. 构建 Provider 特定请求
     * 2. 发送流式请求
     * 3. 过滤和提取内容块
     */
    @Override
    public final Flux<String> chatStream(ChatRequest request) {
        P providerRequest = convertRequest(request, true);

        return executeStreamRequest(providerRequest)
                .filter(this::shouldProcessEvent)
                .mapNotNull(this::extractStreamContent);
    }

    // ==================== 子类实现的模板钩子方法 ====================

    /**
     * 将通用请求转换为 Provider 特定请求
     *
     * @param request 通用聊天请求
     * @param stream 是否流式请求
     * @return Provider 特定的请求对象
     */
    protected abstract P convertRequest(ChatRequest request, boolean stream);

    /**
     * 将 Provider 响应转换为通用响应
     *
     * @param rawResponse Provider 原始响应
     * @return 通用聊天响应
     */
    protected abstract ChatResponse<R> convertResponse(R rawResponse);

    /**
     * 执行非流式请求
     *
     * @param providerRequest Provider 特定请求
     * @return 响应Publisher
     */
    protected abstract Mono<R> executeRequest(P providerRequest);

    /**
     * 执行流式请求
     *
     * @param providerRequest Provider 特定请求
     * @return SSE 事件流
     */
    protected abstract Flux<ServerSentEvent<String>> executeStreamRequest(P providerRequest);

    /**
     * 判断是否处理该 SSE 事件
     *
     * @param sse 服务器发送事件
     * @return true 表示处理该事件
     */
    protected abstract boolean shouldProcessEvent(ServerSentEvent<String> sse);

    /**
     * 从 SSE 事件中提取文本内容
     *
     * @param sse 服务器发送事件
     * @return 文本内容，null 表示跳过
     */
    protected abstract String extractStreamContent(ServerSentEvent<String> sse);

    /**
     * 获取 API 端点
     *
     * @return 完整的 API URL
     */
    protected abstract String getEndpoint();

    // ==================== 公共工具方法 ====================

    /**
     * 将 JsonNode 转换为 Map
     *
     * @param node JSON 节点
     * @return Map 对象
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> toJsonMap(JsonNode node) {
        if (node == null) {
            return new HashMap<>();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    /**
     * 通用错误处理
     *
     * @param response HTTP 响应
     * @return 错误信息
     */
    protected Mono<? extends Throwable> handleError(WebClient.ResponseSpec response) {
        return response.bodyToMono(String.class)
                .flatMap(body -> Mono.error(
                        new RuntimeException("API 错误: " + body)));
    }
}
