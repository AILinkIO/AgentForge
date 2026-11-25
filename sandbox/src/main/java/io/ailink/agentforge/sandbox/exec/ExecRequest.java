package io.ailink.agentforge.sandbox.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 起进程的请求（argv，⛔ 拼 shell 命令行）。不可变；用 {@code withXxx} 派生。
 *
 * @param arguments          {@code [程序, 参数…]}
 * @param workingDirectory   工作目录；{@code null} ⇒ 沙盒取上下文的 cwd，本机后端取 JVM 的 cwd
 * @param environment        环境覆盖；值为 {@code null} 表示删除该变量（所以用 {@link LinkedHashMap}，⛔ {@code Map.copyOf}）
 * @param inheritEnvironment 继承宿主环境（缺省 true）
 * @param tty                要伪终端（本库不提供，后端会拒）
 * @param allowBatchFile     Windows 上允许直接跑 .bat / .cmd（参数转义有已知注入问题，缺省拒）
 * @param timeoutMs          执行时限（毫秒，必须 &gt; 0）；{@code null} ⇒ 不限。只有 {@link Exec#run} 看它
 */
public record ExecRequest(List<String> arguments, String workingDirectory, Map<String, String> environment,
                          boolean inheritEnvironment, boolean tty, boolean allowBatchFile, Long timeoutMs) {

    public ExecRequest {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        environment = environment == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(environment));
    }

    /** 最常用的形状：argv + 工作目录，继承环境。 */
    public static ExecRequest of(List<String> arguments, String workingDirectory) {
        return new ExecRequest(arguments, workingDirectory, Map.of(), true, false, false, null);
    }

    public static ExecRequest of(String... arguments) {
        return of(List.of(arguments), null);
    }

    public ExecRequest withArguments(List<String> value) {
        return new ExecRequest(value, workingDirectory, environment, inheritEnvironment, tty, allowBatchFile, timeoutMs);
    }

    public ExecRequest withWorkingDirectory(String value) {
        return new ExecRequest(arguments, value, environment, inheritEnvironment, tty, allowBatchFile, timeoutMs);
    }

    /** 整个替换环境覆盖。 */
    public ExecRequest withEnvironment(Map<String, String> value) {
        return new ExecRequest(arguments, workingDirectory, value, inheritEnvironment, tty, allowBatchFile, timeoutMs);
    }

    /** 在现有覆盖上并入 {@code extra}（同名覆盖）。 */
    public ExecRequest mergeEnvironment(Map<String, String> extra) {
        Map<String, String> m = new LinkedHashMap<>(environment);
        m.putAll(extra);
        return withEnvironment(m);
    }

    public ExecRequest withInheritEnvironment(boolean value) {
        return new ExecRequest(arguments, workingDirectory, environment, value, tty, allowBatchFile, timeoutMs);
    }

    public ExecRequest withTty(boolean value) {
        return new ExecRequest(arguments, workingDirectory, environment, inheritEnvironment, value, allowBatchFile, timeoutMs);
    }

    public ExecRequest withAllowBatchFile(boolean value) {
        return new ExecRequest(arguments, workingDirectory, environment, inheritEnvironment, tty, value, timeoutMs);
    }

    public ExecRequest withTimeoutMs(Long value) {
        return new ExecRequest(arguments, workingDirectory, environment, inheritEnvironment, tty, allowBatchFile, value);
    }
}
