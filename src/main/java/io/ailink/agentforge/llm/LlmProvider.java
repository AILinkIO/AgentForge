package io.ailink.agentforge.llm;

import io.ailink.agentforge.llm.dto.ChatRequest;
import io.ailink.agentforge.llm.dto.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * LLM 提供者接口
 *
 * 定义与大语言模型交互的标准方法。
 * 支持同步、异步和流式三种调用方式。
 *
 * 实现类应处理：
 * - HTTP 请求构建
 * - 响应解析
 * - 错误处理
 * - 重试机制
 *
 * 使用示例：
 * <pre>
 * ChatRequest request = ChatRequest.builder()
 *     .messages(List.of(ChatMessage.user("你好")))
 *     .build();
 *
 * // 同步调用
 * ChatResponse response = provider.chat(request);
 *
 * // 流式调用
 * Flux&lt;String&gt; stream = provider.chatStream(request);
 * </pre>
 */
public interface LlmProvider {

    /**
     * 同步聊天
     *
     * @param request 聊天请求
     * @return 聊天响应
     */
    ChatResponse<?> chat(ChatRequest request);

    /**
     * 异步聊天
     *
     * @param request 聊天请求
     * @return 异步响应
     */
    Mono<? extends ChatResponse<?>> chatAsync(ChatRequest request);

    /**
     * 流式聊天
     *
     * @param request 聊天请求
     * @return 内容流（每个元素是一段文本）
     */
    Flux<String> chatStream(ChatRequest request);
}
