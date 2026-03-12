package io.ailink.agentforge.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry toolRegistry;

    public ToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public ToolResult execute(ToolCall toolCall) {
        String toolName = toolCall.name();
        String callId = toolCall.id();

        log.info("Executing tool: {} (id: {})", toolName, callId);

        return toolRegistry.getTool(toolName)
                .map(tool -> {
                    try {
                        return tool.execute(toolCall.arguments());
                    } catch (Exception e) {
                        log.error("Tool execution failed: {}", toolName, e);
                        return ToolResult.error(callId, "Execution error: " + e.getMessage());
                    }
                })
                .orElseGet(() -> {
                    log.warn("Tool not found: {}", toolName);
                    return ToolResult.error(callId, "Tool not found: " + toolName);
                });
    }

    public List<ToolResult> executeAll(List<ToolCall> toolCalls) {
        return toolCalls.stream()
                .map(this::execute)
                .toList();
    }
}
