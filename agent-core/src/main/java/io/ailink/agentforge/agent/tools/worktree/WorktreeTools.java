package io.ailink.agentforge.agent.tools.worktree;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Args;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.util.Fmt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 五件 worktree 工具（coxswain {@code tools/worktree.ss}）。
 *
 * <p>⚠️ **只认名字**是这一组的安全机制：remove / reset 不收任意目录，只收 worktree-list 列出来的名字，
 * 并且要求 git 登记的目录在本工具的容器里。worktree 在工作根**外面**，路径限定那道闸管不到它；
 * 名字 → 目录 → git 认账 → 才操作，两道闸都过才动手。
 *
 * <p>除 list 外都 {@code sensitive}：建树、整棵砍掉（连分支）、{@code reset --hard + clean -ffdx}。
 * 主工作树（项目本体）永远不能被 remove / reset。
 */
public final class WorktreeTools {
    private WorktreeTools() { }

    /** 工具表里的顺序：list 在只读那一段，其余四件在后面（见 coxswain tools.ss）。 */
    public static List<AgentTool> tools() {
        return List.of(worktreeList(), worktreeCreate(), worktreeRemove(), worktreeReset(), worktreeEnter());
    }

    /** linked 工作树：(name = basename(dir), branch|null, dir)。主工作树永远不出现。 */
    record Linked(String name, String branch, String dir) { }

    static List<Linked> linked(Path root) {
        List<GitWorktree.Entry> es;
        try {
            es = GitWorktree.list(root);
        } catch (GitWorktree.GitException e) {
            throw new ToolException(e.getMessage());
        }
        List<Linked> out = new ArrayList<>();
        for (GitWorktree.Entry e : es) {
            if (e.kind() != GitWorktree.Kind.LINKED) continue;
            Path d = Path.of(e.dir());
            out.add(new Linked(d.getFileName() == null ? e.dir() : d.getFileName().toString(), e.branch(), e.dir()));
        }
        return out;
    }

    static Linked findByName(List<Linked> es, String name) {
        for (Linked l : es) if (l.name.equals(name)) return l;
        return null;
    }

    // ── worktree-list ──────────────────────────────────────────────────

    public static AgentTool worktreeList() {
        return AgentTool.builder("worktree-list")
                .description("列出当前项目的 worktree（不含主工作树本身）。"
                        + "每个 worktree 都有自己的分支，主分支上是用户正在看的项目，"
                        + "可以在别的分支上做实验、改完再合并回去。")
                .readOnly().concurrencySafe()
                .handler((env, a) -> {
                    Path root = env.workspace().root();
                    if (!GitWorktree.gitRepo(root)) {
                        throw new ToolException(Fmt.q(root) + " 不是一个 git 仓库（没有 .git）。worktree 工具仅在 git 项目下可用");
                    }
                    List<Linked> es = linked(root);
                    if (es.isEmpty()) return "0 个 worktree（没有链接出去的额外工作树）。";
                    StringBuilder b = new StringBuilder().append(es.size()).append(" 个 worktree\n");
                    for (int i = 0; i < es.size(); i++) {
                        Linked l = es.get(i);
                        if (i > 0) b.append('\n');
                        // branch 为空 = detached，写明，免得模型拿空串当分支名
                        b.append(l.name).append(l.branch != null ? "  " + l.branch : "  (detached HEAD)").append("  ").append(l.dir);
                    }
                    return b.toString();
                })
                .build();
    }

    // ── worktree-enter ─────────────────────────────────────────────────

    public static AgentTool worktreeEnter() {
        return AgentTool.builder("worktree-enter")
                .description("切到一棵 worktree（worktree-create 建的那种，或 worktree-list 列出来的）：编辑器与工具"
                        + "沙箱一起换到那个目录。⚠️ 切换在**这一轮跑完之后**才生效 —— 这一轮里剩下的工具调用"
                        + "仍在原项目；要在新工作树里干活，先结束这一轮。")
                .required("path", "string", "worktree 的目录（worktree-create 回的「目录」那一行）")
                .sensitive()
                .handler(WorktreeTools::enter)
                .build();
    }

    static String enter(ToolEnv env, Args a) {
        String path = a.str("path");
        WorktreeEnter proc = env.service(WorktreeEnter.class);
        if (proc == null) proc = env.workspace().get(WorktreeEnter.class);
        Path p = path == null ? null : Path.of(path);
        if (p == null || !Files.isDirectory(p)) throw new ToolException(Fmt.q(path) + " 不是一个目录");
        if (!GitWorktree.gitRepo(p)) throw new ToolException(Fmt.q(path) + " 不是一棵 git 工作树（没有 .git）");
        if (proc == null) throw new ToolException("这个环境里进不了 worktree（宿主没接线：脚本 / 离线模式）");
        proc.requestEnter(p.toAbsolutePath().normalize());
        return "已请求切到 " + path + "。\n"
                + "⚠️ 切换在这一轮跑完之后生效：这一轮里剩下的工具调用仍在原项目根，"
                + "要在新工作树里干活，先结束这一轮（说一句要做什么即可）。";
    }

    // ── worktree-create ────────────────────────────────────────────────

    public static AgentTool worktreeCreate() {
        return AgentTool.builder("worktree-create")
                .description("给当前项目建一个新的 worktree —— 独立的工作树 + 独立的分支，放在项目"
                        + "**旁边**的 " + GitWorktree.CONTAINER + "/ 容器里（不污染当前目录的 git status）。"
                        + "用来并行做几件事不互相踩：一棵做实验、一棵留着原样、一棵做测试。")
                .required("name", "string", "worktree 名字，会成为分支名 " + GitWorktree.BRANCH_PREFIX
                        + "<名字> 的一部分（小写字母、数字、连字符）")
                .sensitive()
                .handler(WorktreeTools::create)
                .build();
    }

    static String create(ToolEnv env, Args a) {
        Path root = env.workspace().root();
        String name = a.requireStr("name");
        if (!GitWorktree.gitRepo(root)) throw new ToolException(Fmt.q(root) + " 不是一个 git 仓库");
        String slug = GitWorktree.slugify(name);
        if (slug.isEmpty()) {
            throw new ToolException("worktree 名字 " + Fmt.q(name) + " slug 出来是空的（小写字母、数字、连字符之外都不算）。换一个名字");
        }
        GitWorktree.Picked pk = GitWorktree.generateUnique(root, slug);
        if (pk == null) throw new ToolException(GitWorktree.NAME_ATTEMPTS + " 次都没找到一个不冲突的 " + Fmt.q(slug) + " 后缀。换一个名字吧");
        try { Files.createDirectories(GitWorktree.worktreesRoot(root)); } catch (IOException ignored) { }
        try {
            GitWorktree.add(root, pk.branch(), pk.dir());
        } catch (GitWorktree.GitException e) {
            throw new ToolException("建 worktree 失败：" + e.getMessage());
        }
        return "已创建 worktree " + pk.name() + "\n  分支：" + pk.branch() + "\n  目录：" + pk.dir() + "\n";
    }

    // ── worktree-remove ────────────────────────────────────────────────

    public static AgentTool worktreeRemove() {
        return AgentTool.builder("worktree-remove")
                .description("删掉一个 worktree（连同它自己的分支）。**先 worktree-list 看一眼要删哪个，"
                        + "再用 worktree-list 列出来的那个名字传进来** —— 不接受任意路径，"
                        + "这是为了避免误删。")
                .required("name", "string", "要删掉的 worktree 名字（worktree-list 列出来的）")
                .sensitive()
                .handler(WorktreeTools::remove)
                .build();
    }

    static String remove(ToolEnv env, Args a) {
        Path root = env.workspace().root();
        String name = a.requireStr("name");
        Linked hit = findByName(linked(root), name);
        if (hit == null) {
            if (Files.exists(GitWorktree.nameToDir(root, name), LinkOption.NOFOLLOW_LINKS)) {
                throw new ToolException(Fmt.q(name) + " 看起来是上一轮没建成功留下的残骸（目录在、git 不认）。换个名字，或让用户手动清掉");
            }
            throw new ToolException(Fmt.q(name) + " 不是一个 worktree（worktree-list 里没有它）。先 worktree-list 看一眼");
        }
        if (!inContainer(root, hit.dir)) {
            throw new ToolException(Fmt.q(name) + " 在 git 里登记的目录 " + Fmt.q(hit.dir) + " 不在 agentforge 的 worktrees 容器里 —— 它不是这套工具建的，不删");
        }
        if (sameDir(Path.of(hit.dir), root)) throw new ToolException("这是项目的主工作树本身，不能删");
        try {
            GitWorktree.remove(root, hit.dir, hit.branch);
        } catch (GitWorktree.GitException e) {
            throw new ToolException("删 worktree 失败：" + e.getMessage());
        }
        String tail;
        if (hit.branch == null) tail = "";
        else if (GitWorktree.ownBranch(hit.branch)) tail = "（分支 " + hit.branch + " 也已删除）";
        else tail = "（分支 " + hit.branch + " 不是 " + GitWorktree.BRANCH_PREFIX + " 建的，保留未删）";
        return "已删除 worktree " + name + tail;
    }

    // ── worktree-reset ─────────────────────────────────────────────────

    public static AgentTool worktreeReset() {
        return AgentTool.builder("worktree-reset")
                .description("把 worktree 的工作树**重置**到指定 base：丢弃所有未提交的改动、"
                        + "未跟踪的文件、连子模块里的改动一起清掉。要在哪个工作树上做实验、"
                        + "做完想推倒重来时用它。⚠️ 工作树里的本地改动**全部丢失**，没问过用户。"
                        + "**先 worktree-list 看一眼要重置哪个**，再用那个名字传进来。")
                .required("name", "string", "要重置的 worktree 名字")
                .optional("base", "string", "重置到哪个提交/分支；默认自动探测 origin/HEAD", "")
                .sensitive()
                .handler(WorktreeTools::reset)
                .build();
    }

    static String reset(ToolEnv env, Args a) {
        Path root = env.workspace().root();
        String name = a.requireStr("name");
        String base = a.str("base", "");
        Linked hit = findByName(linked(root), name);
        if (hit == null) throw new ToolException(Fmt.q(name) + " 不是一个 worktree（worktree-list 里没有它）。先 worktree-list 看一眼");
        if (!inContainer(root, hit.dir)) {
            throw new ToolException(Fmt.q(name) + " 在 git 里登记的目录 " + Fmt.q(hit.dir) + " 不在 agentforge 的 worktrees 容器里 —— 它不是这套工具建的，不重置");
        }
        if (sameDir(Path.of(hit.dir), root)) throw new ToolException("这是项目的主工作树本身，不能重置");
        String resolved = base;
        if (resolved.isEmpty()) {
            resolved = GitWorktree.defaultBranch(root);
            // 探测失败就让模型显式传，不猜 main —— 真正的主分支不叫 main 的项目会被重置错
            if (resolved == null) {
                throw new ToolException("探测默认分支失败：" + Fmt.q(root) + " 没有 origin/HEAD。请在 base 参数里显式传一个，比如 main 或 origin/main");
            }
        }
        String left;
        try {
            left = GitWorktree.reset(Path.of(hit.dir), resolved);
        } catch (GitWorktree.GitException e) {
            throw new ToolException("重置 worktree 失败：" + e.getMessage());
        }
        if (left != null) return "worktree " + name + " 已经重置到 " + resolved + "，但仍有未清掉的内容：\n" + left;
        return "worktree " + name + " 已经重置到 " + resolved + "，工作树干净";
    }

    // ── 判据 ───────────────────────────────────────────────────────────

    /** git 登记的目录在不在本工具的容器里（字面比一次、realpath 再比一次：git 报的多半是 realpath）。 */
    static boolean inContainer(Path root, String dir) {
        Path c = GitWorktree.worktreesRoot(root);
        Path d = Path.of(dir).toAbsolutePath().normalize();
        if (d.startsWith(c) && !d.equals(c)) return true;
        try {
            Path rc = c.toRealPath();
            Path rd = d.toRealPath();
            return rd.startsWith(rc) && !rd.equals(rc);
        } catch (IOException e) {
            return false;
        }
    }

    static boolean sameDir(Path a, Path b) {
        if (a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize())) return true;
        try { return Files.isSameFile(a, b); } catch (IOException e) { return false; }
    }
}
