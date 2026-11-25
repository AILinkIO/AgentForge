package io.ailink.agentforge.agent.cost;

import io.ailink.agentforge.agent.models.ModelFacts;

/** 比价 / 路由的一个候选：标识（多半是模型 ref）+ 目录事实。 */
public record Candidate(String ref, ModelFacts facts) { }
