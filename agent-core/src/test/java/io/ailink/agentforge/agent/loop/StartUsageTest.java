package io.ailink.agentforge.agent.loop;

import com.sun.net.httpserver.HttpServer;
import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Anthropic 官方的用量形状：输入 / 缓存读写只在 message_start 里，message_delta 只带 output_tokens。
 * 两条 caller 都要保住 message_start 的缓存数（不能被 message_delta 的"没写"盖成 0）。
 *
 * <p>⚠️ Spring AI 2.0 的 AnthropicChatModel.StreamingState 只从 message_start 记下 inputTokens，缓存读写数丢在框架里，
 * 我们这层拿不到 —— 所以默认策略让 Anthropic 线格式一律走原生 caller（{@code CallerPolicy.DEFAULT}）。
 * Spring AI 那条留作记录：哪天上游修了，去掉 @Disabled 就能看到。
 */
class StartUsageTest {
    private HttpServer server;
    private String base;

    private static final String SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","model":"m","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1000,"output_tokens":1,"cache_read_input_tokens":4000,"cache_creation_input_tokens":500}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":20}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] b = SSE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "text/event-stream");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private static ChatRequest req() {
        return new ChatRequest("sys", List.of(Message.user("hi")), List.of(), RequestOptions.of("m").withMaxTokens(100), null);
    }

    private static void check(Usage u) {
        assertEquals(1000, u.input());
        assertEquals(4000, u.cacheRead());
        assertEquals(500, u.cacheWrite());
        assertEquals(20, u.output());
    }

    @Test
    @Disabled("Spring AI 2.0 丢掉 message_start 的缓存数（见类注释）；默认已不走这条")
    void springAiPath() {
        AnthropicChatModel model = AnthropicChatModel.builder()
                .options(AnthropicChatOptions.builder().apiKey("test").baseUrl(base).model("m").maxTokens(100).build())
                .build();
        SpringAiModelCaller caller = new SpringAiModelCaller(model,
                (r, cbs) -> AnthropicChatOptions.builder().apiKey("test").baseUrl(base).model("m").maxTokens(100).build(), true);
        check(caller.call(req(), DeltaListener.NONE, new CancelToken()).usage());
    }

    @Test
    void nativePath() {
        AnthropicHttpCaller caller = new AnthropicHttpCaller(base, "test", false, Map.of(), Duration.ofSeconds(5));
        check(caller.call(req(), DeltaListener.NONE, new CancelToken()).usage());
    }
}
