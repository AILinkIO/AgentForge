package io.ailink.agentforge.llm.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.llm.dto.ChatResponse;
import io.ailink.agentforge.llm.dto.TokenUsage;
import io.ailink.agentforge.llm.claude.dto.ClaudeResponse;
import io.ailink.agentforge.tool.ToolCall;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ClaudeChatResponse implements ChatResponse<ClaudeResponse> {

    private final ClaudeResponse raw;
    private final List<ToolCall> toolCalls;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClaudeChatResponse(ClaudeResponse raw) {
        this.raw = raw;
        this.toolCalls = extractToolCalls(raw);
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
                ? TokenUsage.of(raw.usage().inputTokens(), raw.usage().outputTokens())
                : null;
    }

    @Override
    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    @Override
    public ClaudeResponse rawResponse() {
        return raw;
    }

    @Override
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    private List<ToolCall> extractToolCalls(ClaudeResponse response) {
        if (response.content() == null) {
            return Collections.emptyList();
        }
        return response.content().stream()
                .filter(block -> "tool_use".equals(block.type()))
                .map(block -> {
                    JsonNode input = block.input() != null 
                            ? objectMapper.valueToTree(block.input()) 
                            : objectMapper.createObjectNode();
                    return new ToolCall(block.id(), block.name(), input);
                })
                .toList();
    }
}
