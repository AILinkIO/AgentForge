package io.ailink.agentforge.agent.model;

public enum Role {
    USER, ASSISTANT;

    public String wire() { return this == USER ? "user" : "assistant"; }
}
