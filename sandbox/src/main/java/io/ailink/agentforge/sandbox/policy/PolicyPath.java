package io.ailink.agentforge.sandbox.policy;

import java.util.Objects;

/** 条目的路径：字面路径 / glob（只配 {@link AccessMode#DENY}）/ 特殊路径。 */
public sealed interface PolicyPath {

    /** 字面路径（相对路径按上下文的 cwd 解析）。 */
    record Literal(String path) implements PolicyPath {
        public Literal {
            Objects.requireNonNull(path, "path");
        }
    }

    /** git 风格 glob（相对按 cwd、{@code ~/} 按家目录解析）。 */
    record GlobPattern(String pattern) implements PolicyPath {
        public GlobPattern {
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    /** 特殊路径；{@code subpath} 只对 {@link SpecialKind#PROJECT_ROOTS} 有意义（逃出根的子路径被忽略）。 */
    record Special(SpecialKind kind, String subpath) implements PolicyPath {
        public Special {
            Objects.requireNonNull(kind, "kind");
        }
    }

    static PolicyPath literal(String path) {
        return new Literal(path);
    }

    static PolicyPath glob(String pattern) {
        return new GlobPattern(pattern);
    }

    static PolicyPath special(SpecialKind kind) {
        return new Special(kind, null);
    }

    static PolicyPath special(SpecialKind kind, String subpath) {
        return new Special(kind, subpath);
    }
}
