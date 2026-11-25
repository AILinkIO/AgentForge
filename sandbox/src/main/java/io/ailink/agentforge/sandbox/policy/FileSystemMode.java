package io.ailink.agentforge.sandbox.policy;

/** 文件系统模式：受限（按条目）/ 不限 / 已在外部沙盒里（不限，且不需要平台沙盒）。 */
public enum FileSystemMode {
    RESTRICTED("restricted"), UNRESTRICTED("unrestricted"), EXTERNAL("external");

    private final String jsonName;

    FileSystemMode(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
