package io.ailink.agentforge.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(Optional<List<Tool>> toolList) {
        toolList.ifPresent(list -> {
            for (Tool tool : list) {
                register(tool);
            }
        });
    }

    public void register(Tool tool) {
        String name = tool.name();
        if (tools.containsKey(name)) {
            log.warn("Tool '{}' already registered, overwriting", name);
        }
        tools.put(name, tool);
        log.debug("Registered tool: {}", name);
    }

    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> getToolDefinitions() {
        return tools.values().stream()
                .map(ToolDefinition::from)
                .toList();
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    public Collection<Tool> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
    }
}
