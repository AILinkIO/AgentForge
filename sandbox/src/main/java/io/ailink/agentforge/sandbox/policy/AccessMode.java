package io.ailink.agentforge.sandbox.policy;

/** 条目的访问级别；同样具体的条目冲突时按 {@code READ < WRITE < DENY} 取大（{@link #rank()}）。 */
public enum AccessMode {
    READ("read"), WRITE("write"), DENY("deny");

    private final String jsonName;

    AccessMode(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /** 冲突优先级。 */
    public int rank() {
        return ordinal();
    }
}
