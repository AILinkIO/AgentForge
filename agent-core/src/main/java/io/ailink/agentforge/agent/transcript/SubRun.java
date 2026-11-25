package io.ailink.agentforge.agent.transcript;

import java.util.ArrayList;
import java.util.List;

/**
 * 挂在某次工具调用（task）底下的"一趟带步骤的活"（coxswain {@code sub-run}）。
 *
 * <p>⚠️ 认的是**那次 task 调用的 tool_use_id**：位置因此是精确的，跨轮重投影能贴回原处。这一份不按轮清，
 * 只有开新对话才清。线程安全（子 agent 在工具线程上记步，UI 在别的线程上读）。
 */
public final class SubRun {
    private final String id;
    private final String desc;
    private final long start = System.currentTimeMillis();
    private final List<SubStep> steps = new ArrayList<>();
    private final List<SubStep> live = new ArrayList<>();
    private volatile String status;          // null = 还在跑
    private volatile Long ms;
    private volatile boolean background;
    private volatile String who;
    private volatile String model;
    private volatile String why;

    public SubRun(String id, String desc) {
        this.id = id;
        this.desc = desc == null ? "" : desc;
    }

    public String id() { return id; }

    public String desc() { return desc; }

    /** completed / return-direct / max-iterations / stopped / error …；null = 还在跑。 */
    public String status() { return status; }

    public boolean running() { return status == null; }

    public boolean ok() { return "completed".equals(status) || "return-direct".equals(status) || "done".equals(status); }

    public Long ms() { return ms; }

    public boolean background() { return background; }

    public void background(boolean v) { this.background = v; }

    public String who() { return who; }

    public void who(String v) { this.who = v; }

    public String model() { return model; }

    public void model(String v) { this.model = v; }

    public String why() { return why; }

    /** 跑完的步骤 + 正在跑的那几步（按发生顺序）。 */
    public synchronized List<SubStep> allSteps() {
        List<SubStep> out = new ArrayList<>(steps);
        out.addAll(live);
        return out;
    }

    public synchronized void step(SubStep s) { live.add(s); }

    /**
     * 收尾：给了步骤表就用它（带结果摘要），否则把跑着时记下的那几步转正。⚠️ 已经收过尾的不再收（后台那趟可能
     * 被父会话那边先封了"被停下"，晚到的结果不许把它改回 completed）。
     */
    public synchronized boolean finish(String status, List<SubStep> finalSteps, String why) {
        if (this.status != null && !this.status.equals(status)) return false;
        steps.addAll(finalSteps == null || finalSteps.isEmpty() ? live : finalSteps);
        live.clear();
        this.status = status;
        if (why != null && !why.isEmpty()) this.why = why;
        this.ms = Math.max(0, System.currentTimeMillis() - start);
        return true;
    }
}
