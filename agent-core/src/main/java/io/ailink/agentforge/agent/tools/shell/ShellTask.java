package io.ailink.agentforge.agent.tools.shell;

import java.nio.file.Path;

/**
 * 一条后台命令（run-command 的 {@code run-in-background}）。表只进不出：跑完翻 state 留着，
 * 模型回头问"bg-2 呢"还有答案。
 */
public final class ShellTask {
    public enum State { RUNNING, DONE, FAILED }

    private final String id;
    private final String command;
    private final Path log;
    private final long startedMs;
    private volatile long pid;
    private volatile State state = State.RUNNING;
    private volatile Integer exitCode;

    ShellTask(String id, String command, Path log, long startedMs) {
        this.id = id;
        this.command = command;
        this.log = log;
        this.startedMs = startedMs;
    }

    /** {@code bg-N}，会话内自增。 */
    public String id() { return id; }

    /** 模型写的原命令（未经包裹）。 */
    public String command() { return command; }

    /** 日志绝对路径（临时目录下，不在工作根里）。 */
    public Path log() { return log; }

    public long startedMs() { return startedMs; }

    /** spawn 之后回填；0 = 还没起来。 */
    public long pid() { return pid; }

    public State state() { return state; }

    public Integer exitCode() { return exitCode; }

    void pid(long p) { this.pid = p; }

    void finish(int code) {
        this.exitCode = code;
        this.state = code == 0 ? State.DONE : State.FAILED;
    }
}
