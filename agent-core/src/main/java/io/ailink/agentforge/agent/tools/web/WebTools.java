package io.ailink.agentforge.agent.tools.web;

import io.ailink.agentforge.agent.tool.AgentTool;

import java.util.List;

/** 出网的两件：web-fetch、web-search（都 sensitive + concurrency-safe）。 */
public final class WebTools {
    private WebTools() { }

    public static List<AgentTool> tools() { return List.of(WebFetch.tool(), WebSearch.tool()); }
}
