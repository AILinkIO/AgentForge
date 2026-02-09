package io.ailink.agentforge.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.llm.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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

class OpenAiProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ClientRequest capturedRequest;

    private OpenAiProvider createProvider(ExchangeFunction exchangeFunction) {
        OpenAiProperties props = new OpenAiProperties();
        props.setApiKey("test-api-key");
        props.setBaseUrl("https://api.openai.com");
        props.setDefaultModel("gpt-4o-test");
        props.setDefaultMaxTokens(512);

        WebClient webClient = WebClient.builder()
                .exchangeFunction(exchangeFunction)
                .build();
        return new OpenAiProvider(webClient, props, objectMapper);
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
            {"id":"chatcmpl-123","model":"gpt-4o-test",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hello, world!"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""";

    @Test
    void chat_returnsResponseFromApi() {
        OpenAiProvider provider = createProvider(jsonExchange(OK_RESPONSE));

        ChatResponse resp = provider.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("Hi")))
                .build());

        assertEquals("chatcmpl-123", resp.id());
        assertEquals("Hello, world!", resp.content());
        assertEquals("gpt-4o-test", resp.model());
        assertEquals("stop", resp.stopReason());
        assertEquals(10, resp.usage().inputTokens());
        assertEquals(5, resp.usage().outputTokens());
    }

    @Test
    void chat_sendsCorrectHeaders() {
        OpenAiProvider provider = createProvider(jsonExchange(OK_RESPONSE));

        provider.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("test")))
                .build());

        assertEquals("https://api.openai.com/v1/chat/completions", capturedRequest.url().toString());
        assertEquals("Bearer test-api-key", capturedRequest.headers().getFirst("Authorization"));
        assertTrue(capturedRequest.headers().getFirst("Content-Type").contains("application/json"));
    }

    @Test
    void chat_usesDefaultModelAndMaxTokens() {
        OpenAiProvider provider = createProvider(jsonExchange(OK_RESPONSE));

        provider.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("test")))
                .build());

        String body = extractRequestBody(capturedRequest);
        assertTrue(body.contains("\"model\":\"gpt-4o-test\""));
        assertTrue(body.contains("\"max_tokens\":512"));
        assertFalse(body.contains("\"stream\""));
    }

    @Test
    void chat_overridesModelAndMaxTokens() {
        OpenAiProvider provider = createProvider(jsonExchange(OK_RESPONSE));

        provider.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("test")))
                .model("gpt-4o-mini")
                .maxTokens(2048)
                .temperature(0.7)
                .build());

        String body = extractRequestBody(capturedRequest);
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""));
        assertTrue(body.contains("\"max_tokens\":2048"));
        assertTrue(body.contains("\"temperature\":0.7"));
    }

    @Test
    void chat_sendsSystemMessageInMessagesArray() {
        OpenAiProvider provider = createProvider(jsonExchange(OK_RESPONSE));

        provider.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("Hi")))
                .system("You are helpful.")
                .build());

        String body = extractRequestBody(capturedRequest);
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"content\":\"You are helpful.\""));
        assertFalse(body.contains("\"system\":"));
    }

    @Test
    void chat_throwsOnApiError() {
        ExchangeFunction errorExchange = request -> Mono.just(
                ClientResponse.create(HttpStatus.UNAUTHORIZED)
                        .header("Content-Type", "application/json")
                        .body("{\"error\":{\"message\":\"Incorrect API key\"}}")
                        .build());

        OpenAiProvider provider = createProvider(errorExchange);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                provider.chat(ChatRequest.builder()
                        .messages(List.of(ChatMessage.user("test")))
                        .build()));
        assertTrue(ex.getMessage().contains("401"));
        assertTrue(ex.getMessage().contains("Incorrect API key"));
    }

    @Test
    void chatStream_extractsTextDeltas() {
        String sse = """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}\r
                \r
                data: {"choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}\r
                \r
                data: {"choices":[{"index":0,"delta":{"content":", world!"},"finish_reason":null}]}\r
                \r
                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\r
                \r
                data: [DONE]\r
                \r
                """;

        ExchangeFunction sseExchange = request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "text/event-stream")
                        .body(sse)
                        .build());

        OpenAiProvider provider = createProvider(sseExchange);

        List<String> chunks = provider.chatStream(ChatRequest.builder()
                        .messages(List.of(ChatMessage.user("Hi")))
                        .build())
                .collectList().block();

        assertNotNull(chunks);
        assertTrue(chunks.contains("Hello"));
        assertTrue(chunks.contains(", world!"));
    }
}
