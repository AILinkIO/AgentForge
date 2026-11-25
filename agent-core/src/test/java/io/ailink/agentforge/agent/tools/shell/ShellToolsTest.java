package io.ailink.agentforge.agent.tools.shell;

import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.ailink.agentforge.agent.util.ToolKit.call;
import static io.ailink.agentforge.agent.util.ToolKit.put;
import static io.ailink.agentforge.agent.util.ToolKit.windows;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ShellToolsTest {
    @TempDir Path root;
    Workspace ws;
    ToolEnv env;

    @BeforeEach
    void setup() throws Exception {
        assumeFalse(windows());
        ws = new Workspace(root.toRealPath());
        env = ToolEnv.of(ws);
        Files.createDirectories(root.resolve("sub"));
    }

    private String run(Object... kv) {
        ToolOutput o = call(ShellTools.runCommand(), env, kv);
        assertFalse(o.error(), o.text());
        return o.text();
    }

    @Test
    void wrapAndSession() {
        ShellSession s = new ShellSession(root);
        String w = s.wrapWithCwdCapture("echo hi");
        assertTrue(w.contains("echo hi"));
        assertTrue(w.contains("_code=$?"));
        assertTrue(w.contains("pwd -P >| "));
        assertTrue(w.contains("exit $_code"));
        assertTrue(w.contains(s.cwdFile().toString()));
    }

    @Test
    void stdoutStderrSeparatedAndExitCode() {
        String r = run("command", "echo out; echo err 1>&2; exit 3");
        assertTrue(r.startsWith("退出码 3\n"), r);
        assertTrue(r.contains("── stdout ──\nout\n"));
        assertTrue(r.contains("── stderr ──\nerr"));
        assertFalse(r.contains("pwd -P"), "包裹的尾巴不混进输出");
        String ok = run("command", "true");
        assertTrue(ok.startsWith("退出码 0"));
        assertTrue(ok.contains("（没有输出）"));
    }

    @Test
    void failedCommandKeepsItsExitCode() {
        String r = run("command", "cat /no/such/file/xyz");
        assertFalse(r.startsWith("退出码 0"), "失败的命令不许报成功");
        assertTrue(r.contains("No such file") || r.contains("没有"), r);
    }

    @Test
    void cdIsRemembered() throws Exception {
        run("command", "cd sub");
        assertEquals(ws.root().resolve("sub"), ShellSession.of(env).cwd());
        String r = run("command", "pwd -P");
        assertTrue(r.contains(ws.root().resolve("sub").toString()), r);
    }

    @Test
    void cdOutOfRootIsReset() {
        String r = run("command", "cd /");
        assertTrue(r.contains("cd 越出了根目录"), r);
        assertEquals(ws.root(), ShellSession.of(env).cwd());
    }

    @Test
    void timeoutMessage() {
        ShellSettings.of(ws).timeoutCapMs(400);
        String r = run("command", "sleep 5", "timeout", 100000);
        assertTrue(r.startsWith("超时（timeout 400 ms，已杀；实际跑了 "), r);
        assertTrue(r.contains("上限 400"));
        assertTrue(r.contains("run-in-background"));
    }

    @Test
    void bigOutputSpills(@TempDir Path spill) {
        io.ailink.agentforge.agent.tools.Limit.spillDir(spill);
        try {
            String r = run("command", "head -c 40000 /dev/zero | tr '\\0' a");
            assertTrue(r.contains("[已截断：stdout 共 40000 个字符"), r.substring(0, Math.min(200, r.length())));
            assertTrue(r.contains("完整内容已存到 " + spill));
        } finally {
            io.ailink.agentforge.agent.tools.Limit.spillDir(null);
        }
    }

    @Test
    void backgroundTask() throws Exception {
        String r = run("command", "echo started; sleep 0.2; echo finished", "run-in-background", true);
        assertTrue(r.startsWith("后台任务已启动：bg-1"), r);
        ShellTask t = ShellSession.of(env).tasks().getFirst();
        assertEquals("bg-1", t.id());
        for (int i = 0; i < 100 && t.state() == ShellTask.State.RUNNING; i++) Thread.sleep(50);
        assertEquals(ShellTask.State.DONE, t.state());
        String log = Files.readString(t.log());
        assertTrue(log.contains("started\nfinished"), log);
        assertTrue(log.contains("[agentforge 后台任务 bg-1 结束：退出码 0]"));
        Files.deleteIfExists(t.log());
    }

    @Test
    void gitMutationDetection() {
        assertTrue(ShellTools.gitMutation("git commit -m x"));
        assertTrue(ShellTools.gitMutation("git -C repo push"));
        assertTrue(ShellTools.gitMutation("git -c a=b --no-pager checkout main"));
        assertFalse(ShellTools.gitMutation("git status"));
        assertFalse(ShellTools.gitMutation("git log --oneline"));
        assertTrue(ShellTools.gitMutation("gh pr create"));
        assertFalse(ShellTools.gitMutation("cd x && git commit"));
    }

    @Test
    void afterGitHookFiresOnSuccessOnly() throws Exception {
        List<String> seen = new ArrayList<>();
        ShellSettings.of(ws).afterGit(seen::add);
        put(root.resolve("git"), "#!/bin/sh\nexit 0\n").toFile().setExecutable(true);
        run("command", "./git commit -m x");          // 不是以 git 开头 → 不叫
        assertEquals(List.of(), seen);
        ShellSettings.of(ws).runner(new CommandRunner() {   // 假执行器：任何命令都成功
            @Override public Result run(Request req) { return new Result(0, new byte[0], new byte[0], false, false, false); }

            @Override public Background spawn(String c, List<String> a, Path d) { throw new UnsupportedOperationException(); }
        });
        run("command", "git commit -m x");
        assertEquals(List.of("git commit -m x"), seen);
    }

    @Test
    void flags() {
        var t = ShellTools.runCommand();
        assertTrue(t.sensitive());
        assertFalse(t.readOnly());
        assertFalse(t.concurrencySafe());
    }
}
