package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Block;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/** 循环里跑过的一件工具（子 agent 回报"做了哪几步"用）。 */
public record Step(String id, String name, ObjectNode input, List<Block> content, boolean error, String kind, long ms) { }
