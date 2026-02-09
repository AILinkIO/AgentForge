package io.ailink.agentforge.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatResponseTest {

    @Test
    void of_createsSimpleChatResponse() {
        TokenUsage usage = new TokenUsage(10, 5);
        ChatResponse<Void> resp = ChatResponse.of("id-1", "hello", "model-1", "stop", usage);

        assertEquals("id-1", resp.id());
        assertEquals("hello", resp.content());
        assertEquals("model-1", resp.model());
        assertEquals("stop", resp.stopReason());
        assertEquals(10, resp.usage().inputTokens());
        assertEquals(5, resp.usage().outputTokens());
        assertNull(resp.rawResponse());
    }

    @Test
    void of_returnsSimpleChatResponseInstance() {
        ChatResponse<Void> resp = ChatResponse.of("id-1", "hello", "model-1", "stop", null);
        assertInstanceOf(SimpleChatResponse.class, resp);
    }

    @Test
    void simpleChatResponse_equalsAndHashCode() {
        TokenUsage usage = new TokenUsage(10, 5);
        SimpleChatResponse a = new SimpleChatResponse("id", "content", "model", "stop", usage);
        SimpleChatResponse b = new SimpleChatResponse("id", "content", "model", "stop", usage);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void simpleChatResponse_toString() {
        SimpleChatResponse resp = new SimpleChatResponse("id", "content", "model", "stop", null);
        String str = resp.toString();
        assertTrue(str.contains("id"));
        assertTrue(str.contains("content"));
        assertTrue(str.contains("model"));
    }
}
