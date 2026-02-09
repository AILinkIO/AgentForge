package io.ailink.agentforge.llm.claude;

import io.ailink.agentforge.llm.ChatResponse;
import io.ailink.agentforge.llm.TokenUsage;
import io.ailink.agentforge.llm.claude.dto.ClaudeResponse;

import java.util.stream.Collectors;

public class ClaudeChatResponse implements ChatResponse<ClaudeResponse> {

    private final ClaudeResponse raw;

    public ClaudeChatResponse(ClaudeResponse raw) {
        this.raw = raw;
    }

    @Override
    public String id() {
        return raw.id();
    }

    @Override
    public String content() {
        return raw.content().stream()
                .filter(block -> "text".equals(block.type()))
                .map(ClaudeResponse.ContentBlock::text)
                .collect(Collectors.joining());
    }

    @Override
    public String model() {
        return raw.model();
    }

    @Override
    public String stopReason() {
        return raw.stopReason();
    }

    @Override
    public TokenUsage usage() {
        return raw.usage() != null
                ? new TokenUsage(raw.usage().inputTokens(), raw.usage().outputTokens())
                : null;
    }

    @Override
    public ClaudeResponse rawResponse() {
        return raw;
    }
}
