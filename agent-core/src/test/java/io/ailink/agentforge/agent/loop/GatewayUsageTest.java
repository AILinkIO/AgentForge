package io.ailink.agentforge.agent.loop;

import com.sun.net.httpserver.HttpServer;
import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 网关形状的用量：message_start 里输入为 0，输入 / 缓存读写的真值只在 message_delta 里
 * （LiteGate 把 OpenAI 上游转成 Anthropic 流时就是这样，合乎 Anthropic 协议）。
 * 两条 caller 都要算出同一份：input 1000（未命中）、cacheRead 4000、cacheWrite 0、output 20。
 */
class GatewayUsageTest {
    private HttpServer server;
    private String base;

    private static final String SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","model":"gw-model","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":0,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"input_tokens":1000,"output_tokens":20,"cache_read_input_tokens":4000,"cache_creation_input_tokens":0}}

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
        return new ChatRequest("sys", List.of(Message.user("hi")), List.of(), RequestOptions.of("gw-model").withMaxTokens(100), null);
    }

    private static void check(Usage u) {
        assertEquals(1000, u.input(), "未命中的输入");
        assertEquals(4000, u.cacheRead());
        assertEquals(0, u.cacheWrite());
        assertEquals(20, u.output());
        assertEquals(0.8, u.cacheHitRate(), 1e-9);
    }

    @Test
    void springAiPath() {
        AnthropicChatModel model = AnthropicChatModel.builder()
                .options(AnthropicChatOptions.builder().apiKey("test").baseUrl(base).model("gw-model").maxTokens(100).build())
                .build();
        SpringAiModelCaller caller = new SpringAiModelCaller(model,
                (r, cbs) -> AnthropicChatOptions.builder().apiKey("test").baseUrl(base).model("gw-model").maxTokens(100).build(), true);
        ModelTurn t = caller.call(req(), DeltaListener.NONE, new CancelToken());
        assertEquals("你好", t.message().text());
        check(t.usage());
    }

    @Test
    void nativePath() {
        AnthropicHttpCaller caller = new AnthropicHttpCaller(base, "test", false, Map.of(), Duration.ofSeconds(5));
        ModelTurn t = caller.call(req(), DeltaListener.NONE, new CancelToken());
        assertEquals("你好", t.message().text());
        check(t.usage());
    }
}
