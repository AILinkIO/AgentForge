package io.ailink.agentforge.agent.transcript;

/** 子 agent 的一步：工具名、参数单行、出错没有、结果第一行（还在跑时后两项为 null / false）。 */
public record SubStep(String name, String args, boolean error, String summary) { }
