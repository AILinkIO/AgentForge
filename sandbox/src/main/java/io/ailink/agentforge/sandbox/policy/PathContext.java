package io.ailink.agentforge.sandbox.policy;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.SandboxException;

import java.util.List;
import java.util.Objects;

/**
 * 解析策略条目用的路径上下文（都是绝对路径）。
 *
 * @param cwd                  当前目录（相对路径、glob 的基准；{@code ROOT} 取它所在文件系统）
 * @param projectRoots         项目根；{@code null} / 空 ⇒ 就是 cwd
 * @param temporaryDirectories {@code TMPDIR} 解析成的目录
 * @param homeDirectory        {@code ~/} 开头的 glob 用；{@code null} ⇒ 这类 glob 失败即拒
 */
public record PathContext(String cwd, List<String> projectRoots, List<String> temporaryDirectories, String homeDirectory) {
    public PathContext {
        Objects.requireNonNull(cwd, "cwd");
        if (!LexicalPaths.isAbsolute(cwd)) throw SandboxException.of(ErrorKind.ARGUMENT, "sandbox/path-not-absolute", cwd);
        cwd = LexicalPaths.normalize(cwd);
        projectRoots = projectRoots == null ? null : List.copyOf(projectRoots);
        temporaryDirectories = temporaryDirectories == null ? List.of() : List.copyOf(temporaryDirectories);
    }

    /** 只给 cwd（无临时目录、无家目录）。 */
    public static PathContext of(String cwd) {
        return new PathContext(cwd, null, List.of(), null);
    }

    /**
     * 本机的缺省上下文：临时目录取 POSIX 的 {@code $TMPDIR}（非空且是绝对路径时），家目录取 {@code user.home}。
     */
    public static PathContext forCwd(String cwd, List<String> projectRoots) {
        String tmp = System.getenv("TMPDIR");
        String home = System.getProperty("user.home");
        return new PathContext(cwd, projectRoots,
                tmp != null && !tmp.isEmpty() && LexicalPaths.isAbsolute(tmp) ? List.of(tmp) : List.of(),
                home == null || home.isEmpty() ? null : home);
    }

    public static PathContext forCwd(String cwd) {
        return forCwd(cwd, null);
    }

    public PathContext withHomeDirectory(String home) {
        return new PathContext(cwd, projectRoots, temporaryDirectories, home);
    }

    public PathContext withTemporaryDirectories(List<String> dirs) {
        return new PathContext(cwd, projectRoots, dirs, homeDirectory);
    }

    /** 实际生效的项目根（绝对化、规范化；没给 ⇒ {@code [cwd]}）。 */
    public List<String> effectiveProjectRoots() {
        if (projectRoots == null || projectRoots.isEmpty()) return List.of(cwd);
        return projectRoots.stream().filter(LexicalPaths::isAbsolute).map(LexicalPaths::normalize).toList();
    }
}
