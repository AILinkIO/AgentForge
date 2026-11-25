package io.ailink.agentforge.agent.tools.worktree;

import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.ailink.agentforge.agent.util.ToolKit.call;
import static io.ailink.agentforge.agent.util.ToolKit.put;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorktreeToolsTest {

    @Test
    void parsePorcelain() {
        String text = "worktree /p/main\nHEAD abc\nbranch refs/heads/master\n\n"
                + "worktree /p/.agentforge-worktrees/main/exp\nHEAD def\nbranch refs/heads/agentforge/exp\n\n"
                + "worktree /p/det\nHEAD 123\ndetached\n\n";
        List<GitWorktree.Entry> es = GitWorktree.parse(text);
        assertEquals(3, es.size());
        assertEquals(new GitWorktree.Entry(GitWorktree.Kind.MAIN, "/p/main", "master"), es.get(0));
        assertEquals("agentforge/exp", es.get(1).branch());
        assertEquals(GitWorktree.Kind.LINKED, es.get(1).kind());
        assertNull(es.get(2).branch(), "detached = null");
    }

    @Test
    void parseDropsIncompleteAndUnterminated() {
        assertEquals(1, GitWorktree.parse("worktree /a\nHEAD x\n\nworktree /b\n\n").size(), "只有 worktree 行的段落不算");
        assertEquals(1, GitWorktree.parse("worktree /a\nHEAD x\n\nworktree /b\nHEAD y").size(), "最后一段没换行收尾不算");
        assertEquals(List.of(), GitWorktree.parse(""));
    }

    @Test
    void slugify() {
        assertEquals("fix-login-bug", GitWorktree.slugify("  Fix Login  Bug!! "));
        assertEquals("a-b", GitWorktree.slugify("--a__b--"));
        assertEquals("", GitWorktree.slugify("中文！"));
        assertEquals("", GitWorktree.slugify(null));
    }

    @Test
    void pickUnique(@TempDir Path root) {
        Set<String> takenBranches = Set.of("agentforge/x", "agentforge/x-0001");
        int[] n = {0};
        GitWorktree.Picked p = GitWorktree.pickUnique(root, "x", () -> String.format("%04d", ++n[0]),
                d -> false, takenBranches::contains);
        assertEquals("x-0002", p.name());
        assertEquals("agentforge/x-0002", p.branch());
        assertEquals(GitWorktree.worktreesRoot(root).resolve("x-0002"), p.dir());
        assertNull(GitWorktree.pickUnique(root, "x", () -> "0000", d -> true, b -> false), "26 次都撞上");
        assertTrue(GitWorktree.worktreesRoot(root).toString().contains(".agentforge-worktrees"));
    }

    @Test
    void enterNeedsHostAndGitDir(@TempDir Path root) throws Exception {
        Workspace ws = new Workspace(root);
        ToolOutput notDir = call(WorktreeTools.worktreeEnter(), root, "path", root.resolve("nope").toString());
        assertTrue(notDir.error());
        assertTrue(notDir.text().contains("不是一个目录"));
        ToolOutput notGit = call(WorktreeTools.worktreeEnter(), root, "path", root.toString());
        assertTrue(notGit.text().contains("不是一棵 git 工作树"));
        Files.createDirectories(root.resolve(".git"));
        ToolOutput noHost = call(WorktreeTools.worktreeEnter(), root, "path", root.toString());
        assertTrue(noHost.error());
        assertTrue(noHost.text().contains("宿主没接线"));
        List<Path> asked = new ArrayList<>();
        ToolEnv env = ToolEnv.of(ws).withService(WorktreeEnter.class, asked::add);
        ToolOutput ok = call(WorktreeTools.worktreeEnter(), env, "path", root.toString());
        assertFalse(ok.error(), ok.text());
        assertTrue(ok.text().startsWith("已请求切到 "));
        assertTrue(ok.text().contains("这一轮跑完之后生效"));
        assertEquals(List.of(root.toAbsolutePath().normalize()), asked);
    }

    @Test
    void listRequiresRepo(@TempDir Path root) {
        ToolOutput o = call(WorktreeTools.worktreeList(), root);
        assertTrue(o.error());
        assertTrue(o.text().contains("不是一个 git 仓库"));
    }

    private static boolean haveGit() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> a = new ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "user.email=t@t", "-c", "user.name=t",
                "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main"));
        a.addAll(List.of(args));
        Process p = new ProcessBuilder(a).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }

    @Test
    void createListResetRemove(@TempDir Path parent) throws Exception {
        assumeTrue(haveGit());
        Path root = parent.resolve("proj");
        Files.createDirectories(root);
        git(root, "init", "-q");
        put(root.resolve("a.txt"), "one\n");
        git(root, "add", "a.txt");
        git(root, "commit", "-q", "-m", "init");
        Path real = root.toRealPath();
        ToolEnv env = ToolEnv.of(new Workspace(real));

        assertEquals("0 个 worktree（没有链接出去的额外工作树）。", call(WorktreeTools.worktreeList(), env).text());
        ToolOutput c = call(WorktreeTools.worktreeCreate(), env, "name", "My Exp");
        assertFalse(c.error(), c.text());
        assertTrue(c.text().startsWith("已创建 worktree my-exp\n"), c.text());
        assertTrue(c.text().contains("分支：agentforge/my-exp"));
        Path wt = GitWorktree.nameToDir(real, "my-exp");
        assertTrue(Files.isRegularFile(wt.resolve("a.txt")));

        String l = call(WorktreeTools.worktreeList(), env).text();
        assertTrue(l.startsWith("1 个 worktree\nmy-exp  agentforge/my-exp  "), l);

        // 弄脏再重置
        put(wt.resolve("a.txt"), "dirty\n");
        put(wt.resolve("junk.txt"), "x");
        ToolOutput noBase = call(WorktreeTools.worktreeReset(), env, "name", "my-exp");
        assertTrue(noBase.error(), "没有 origin/HEAD 时不猜 main");
        assertTrue(noBase.text().contains("探测默认分支失败"));
        ToolOutput rs = call(WorktreeTools.worktreeReset(), env, "name", "my-exp", "base", "HEAD");
        assertFalse(rs.error(), rs.text());
        assertTrue(rs.text().endsWith("工作树干净"), rs.text());
        assertEquals("one\n", Files.readString(wt.resolve("a.txt")));
        assertFalse(Files.exists(wt.resolve("junk.txt")));

        ToolOutput bad = call(WorktreeTools.worktreeRemove(), env, "name", "nope");
        assertTrue(bad.error());
        assertTrue(bad.text().contains("worktree-list 里没有它"));
        ToolOutput rm = call(WorktreeTools.worktreeRemove(), env, "name", "my-exp");
        assertFalse(rm.error(), rm.text());
        assertEquals("已删除 worktree my-exp（分支 agentforge/my-exp 也已删除）", rm.text());
        assertFalse(Files.exists(wt));
        assertFalse(GitWorktree.branchExists(real, "agentforge/my-exp"));
    }

    @Test
    void flags() {
        assertTrue(WorktreeTools.worktreeList().readOnly());
        for (var t : List.of(WorktreeTools.worktreeCreate(), WorktreeTools.worktreeRemove(),
                WorktreeTools.worktreeReset(), WorktreeTools.worktreeEnter())) {
            assertTrue(t.sensitive(), t.name());
        }
    }
}
