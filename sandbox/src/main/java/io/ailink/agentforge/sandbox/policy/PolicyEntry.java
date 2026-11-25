package io.ailink.agentforge.sandbox.policy;

import java.util.Objects;

/** 策略条目：路径 + 访问级别。 */
public record PolicyEntry(PolicyPath path, AccessMode access) {
    public PolicyEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(access, "access");
    }

    /** 字面路径条目。 */
    public static PolicyEntry of(String path, AccessMode access) {
        return new PolicyEntry(PolicyPath.literal(path), access);
    }

    public static PolicyEntry of(PolicyPath path, AccessMode access) {
        return new PolicyEntry(path, access);
    }

    /** deny glob 条目。 */
    public static PolicyEntry denyGlob(String pattern) {
        return new PolicyEntry(PolicyPath.glob(pattern), AccessMode.DENY);
    }
}
