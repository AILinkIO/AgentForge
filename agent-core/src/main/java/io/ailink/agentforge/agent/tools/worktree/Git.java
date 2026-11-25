package io.ailink.agentforge.agent.tools.worktree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 跑一次 git（coxswain {@code (coxn git run)}）。固定带一组 {@code -c}：不抢 index.lock、
 * 路径不转义、关 fsmonitor / autocrlf；消息强制英文（{@code LC_MESSAGES=C}），出错文字可预期。
 */
final class Git {
    private Git() { }

    static final List<String> CONFIG = List.of(
            "--no-optional-locks",
            "-c", "core.quotepath=false",
            "-c", "core.fsmonitor=false",
            "-c", "core.autocrlf=false",
            "-c", "core.longpaths=true",
            "-c", "core.symlinks=true");

    static final long TIMEOUT_SECONDS = 300;

    record Out(int code, String stdout, String stderr) {
        boolean ok() { return code == 0; }

        /** "git xxx 退出码 N：stderr 首行"。 */
        String why(String op) {
            String e = stderr.strip();
            int nl = e.indexOf('\n');
            if (nl >= 0) e = e.substring(0, nl);
            return op + " 退出码 " + code + (e.isEmpty() ? "" : "：" + e);
        }
    }

    static List<String> argv(Path dir, List<String> args) {
        List<String> a = new ArrayList<>();
        a.add("git");
        a.addAll(CONFIG);
        if (dir != null) { a.add("-C"); a.add(dir.toString()); }
        a.addAll(args);
        return a;
    }

    /** 跑不起来（没装 git 等）抛 IOException。 */
    static Out run(Path dir, String... args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv(dir, List.of(args)));
        pb.environment().put("LANGUAGE", "C");
        pb.environment().put("LC_MESSAGES", "C");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        p.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        Thread te = Thread.ofVirtual().start(() -> copy(p.getErrorStream(), err));
        copy(p.getInputStream(), out);
        try {
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Out(-1, out.toString(StandardCharsets.UTF_8), "超时");
            }
            te.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("被中断");
        }
        return new Out(p.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void copy(InputStream in, ByteArrayOutputStream to) {
        try (in) { in.transferTo(to); } catch (IOException ignored) { }
    }
}
