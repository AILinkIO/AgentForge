package io.ailink.agentforge.llm.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.llm.*;
import io.ailink.agentforge.llm.claude.dto.ClaudeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ClientRequest capturedRequest;

    private ClaudeProvider createProvider(ExchangeFunction exchangeFunction) {
        ClaudeProperties props = new ClaudeProperties();
        props.setApiKey("test-api-key");
        props.setBaseUrl("https://api.anthropic.com");
        props.setDefaultModel("claude-test");
        props.setDefaultMaxTokens(512);

        WebClient webClient = WebClient.builder()
                .exchangeFunction(exchangeFunction)
                .build();
        return new ClaudeProvider(webClient, props, objectMapper);
    }

    private ExchangeFunction jsonExchange(String json) {
        return request -> {
            capturedRequest = request;
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(json)
                    .build());
        };
    }

    private String extractRequestBody(ClientRequest request) {
        MockClientHttpRequest mock = new MockClientHttpRequest(HttpMethod.POST, request.url());
        request.body().insert(mock, new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        }).block();
        return mock.getBodyAsString().block();
    }

    private static final String OK_RESPONSE = """
            {"id":"msg_123","model":"claude-test","stop_reason":"end_turn",
             "content":[{"type":"text","text":"Hello, world!"}],
             "usage":{"input_tokens":10,"output_tokens":5}}""";

    @Test
    void chat_returnsResponseFromApi() {
        ClaudeProvider provider = createProvider(jsonExchange(OK_RESPONSE));

        ChatResponse<?> resp = provider.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("Hi")))
                .build());

        assertEquals("msg_123", resp.id());
        assertEquals("Hello, world!", resp.content());
        assertEquals("claude-test", resp.model());
        assertEquals("end_turn", resp.stopReason());
        assertEquals(10, resp.usage().inputTokens());
        assertEquals(5, resp.usage().outputTokens());
    }

    @Test
    void chat_sendsCorrectHeaders() {
        ClaudeProvider provider = createProvider(jsonExchange(OK_RESPONSE));

        provider.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("test")))
                .build());

        assertEquals("https://api.anthropic.com/v1/messages", capturedRequest.url().toString());
        assertEquals("test-api-key", capturedRequest.headers().getFirst("x-api-key"));
        assertEquals("2023-06-01", capturedRequest.headers().getFirst("anthropic-version"));
        assertTrue(capturedRequest.headers().getFirst("Content-Type").contains("application/json"));
    }

    @Test
    void chat_usesDefaultModelAndMaxTokens() {
        ClaudeProvider provider = createProvider(jsonExchange(OK_RESPONSE));

        provider.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("test")))
                .build());

        String body = extractRequestBody(capturedRequest);
        assertTrue(body.contains("\"model\":\"claude-test\""));
        assertTrue(body.contains("\"max_tokens\":512"));
        assertFalse(body.contains("\"stream\""));
    }

    @Test
    void chat_overridesModelAndMaxTokens() {
        ClaudeProvider provider = createProvider(jsonExchange(OK_RESPONSE));

        provider.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("test")))
                .model("claude-custom")
                .maxTokens(2048)
                .temperature(0.5)
                .system("You are helpful.")
                .build());

        String body = extractRequestBody(capturedRequest);
        assertTrue(body.contains("\"model\":\"claude-custom\""));
        assertTrue(body.contains("\"max_tokens\":2048"));
        assertTrue(body.contains("\"temperature\":0.5"));
        assertTrue(body.contains("\"system\":\"You are helpful.\""));
    }

    @Test
    void chat_throwsOnApiError() {
        ExchangeFunction errorExchange = request -> Mono.just(
                ClientResponse.create(HttpStatus.UNAUTHORIZED)
                        .header("Content-Type", "application/json")
                        .body("{\"error\":{\"message\":\"Invalid API key\"}}")
                        .build());

        ClaudeProvider provider = createProvider(errorExchange);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                provider.chat(ChatRequest.builder()
                        .messages(List.of(ChatMessage.user("test")))
                        .build()));
        assertTrue(ex.getMessage().contains("401"));
        assertTrue(ex.getMessage().contains("Invalid API key"));
    }

    @Test
    void chatStream_extractsTextDeltas() {
        String sse = """
                event: message_start\r
                data: {"type":"message_start"}\r
                \r
                event: content_block_delta\r
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}\r
                \r
                event: content_block_delta\r
                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":", world!"}}\r
                \r
                event: message_stop\r
                data: {"type":"message_stop"}\r
                \r
                """;

        ExchangeFunction sseExchange = request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "text/event-stream")
                        .body(sse)
                        .build());

        ClaudeProvider provider = createProvider(sseExchange);

        List<String> chunks = provider.chatStream(ChatRequest.builder()
                        .messages(List.of(ChatMessage.user("Hi")))
                        .build())
                .collectList().block();

        assertNotNull(chunks);
        assertEquals(2, chunks.size());
        assertEquals("Hello", chunks.get(0));
        assertEquals(", world!", chunks.get(1));
    }

    @Test
    void chat_rawResponseAccessible() {
        ClaudeProvider provider = createProvider(jsonExchange(OK_RESPONSE));

        ChatResponse<?> resp = provider.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("Hi")))
                .build());

        assertInstanceOf(ClaudeChatResponse.class, resp);
        ClaudeChatResponse claudeResp = (ClaudeChatResponse) resp;
        ClaudeResponse raw = claudeResp.rawResponse();
        assertNotNull(raw);
        assertEquals("msg_123", raw.id());
        assertEquals(1, raw.content().size());
        assertEquals("text", raw.content().getFirst().type());
        assertEquals("Hello, world!", raw.content().getFirst().text());
    }

    @Test
    void chatMessage_factoryMethods() {
        ChatMessage user = ChatMessage.user("hello");
        assertEquals("user", user.role());
        assertEquals("hello", user.content());

        ChatMessage assistant = ChatMessage.assistant("hi");
        assertEquals("assistant", assistant.role());
        assertEquals("hi", assistant.content());
    }
}
