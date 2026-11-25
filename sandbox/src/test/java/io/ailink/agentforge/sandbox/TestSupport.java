package io.ailink.agentforge.sandbox;

import io.ailink.agentforge.sandbox.approval.ApprovalDecision;
import io.ailink.agentforge.sandbox.approval.ApprovalRequest;
import io.ailink.agentforge.sandbox.approval.Approver;
import io.ailink.agentforge.sandbox.exec.Exec;
import io.ailink.agentforge.sandbox.exec.ExecBackend;
import io.ailink.agentforge.sandbox.exec.ExecCapabilities;
import io.ailink.agentforge.sandbox.exec.ExecProcess;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.exec.ExecResult;
import io.ailink.agentforge.sandbox.exec.OutputChunk;
import io.ailink.agentforge.sandbox.policy.AccessMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PolicyEntry;
import io.ailink.agentforge.sandbox.policy.PolicyPath;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;
import io.ailink.agentforge.sandbox.policy.SpecialKind;
import io.ailink.agentforge.sandbox.policy.WritableRoot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** 测试共用件（keelson 的 test_support：假后端 / 假沙盒 / 假审批者、临时目录、路径小件）。 */
public final class TestSupport {
    private TestSupport() {
    }

    public static final String SEP = Platform.isWindows() ? "\\" : "/";

    public static String join(String base, String... parts) {
        StringBuilder sb = new StringBuilder(base);
        for (String p : parts) sb.append(SEP).append(p);
        return sb.toString();
    }

    /** 新临时目录（返回绝对、规范的路径串）。调用方用 {@link #deleteTree} 收尾。 */
    public static String tempDir() throws IOException {
        return Files.createTempDirectory("ks-").toRealPath().toString();
    }

    public static void deleteTree(String dir) {
        for (int i = 0; i < 5; i++) {
            if (!Files.exists(Path.of(dir), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            try (Stream<Path> s = Files.walk(Path.of(dir))) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    try {
                        p.toFile().setWritable(true);
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 重试
                    }
                }
            } catch (Exception ignored) {
                // 重试
            }
        }
    }

    /** 带若干临时目录跑 body，结束删掉。 */
    public interface DirsBody {
        void run(String[] dirs) throws Exception;
    }

    public static void withTempDirs(int n, DirsBody body) throws Exception {
        String[] dirs = new String[n];
        try {
            for (int i = 0; i < n; i++) dirs[i] = tempDir();
            body.run(dirs);
        } finally {
            for (String d : dirs) if (d != null) deleteTree(d);
        }
    }

    public static void mkdirs(String... dirs) throws IOException {
        for (String d : dirs) Files.createDirectories(Path.of(d));
    }

    public static void write(String path, String text) throws IOException {
        Files.writeString(Path.of(path), text);
    }

    public static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    public static boolean exists(String path) {
        return Files.exists(Path.of(path), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean isDir(String path) {
        return Files.isDirectory(Path.of(path), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    public static void symlink(String link, String target) throws IOException {
        Files.createSymbolicLink(Path.of(link), Path.of(target));
    }

    public static void chmod(String path, String perms) throws IOException {
        Files.setPosixFilePermissions(Path.of(path), PosixFilePermissions.fromString(perms));
    }

    public static final PolicyPath ROOT = PolicyPath.special(SpecialKind.ROOT);
    public static final PolicyPath MINIMAL = PolicyPath.special(SpecialKind.MINIMAL);
    public static final PolicyPath PROJECT_ROOTS = PolicyPath.special(SpecialKind.PROJECT_ROOTS);

    public static PolicyEntry e(String path, AccessMode access) {
        return PolicyEntry.of(path, access);
    }

    public static PolicyEntry e(PolicyPath path, AccessMode access) {
        return PolicyEntry.of(path, access);
    }

    public static PolicyEntry denyGlob(String pattern) {
        return PolicyEntry.denyGlob(pattern);
    }

    /** Codex 的受保护名（含宿主自己的 .codex）⇒ 搬 Codex 测试时用。 */
    public static SandboxPolicy withCodexNames(SandboxPolicy pol) {
        return pol.withProtectedNames(List.of(".git", ".agents", ".codex", ".aws")).withHostMetadataNames(List.of(".codex"));
    }

    public static SandboxPolicy restricted(PolicyEntry... entries) {
        return withCodexNames(SandboxPolicy.restricted(List.of(entries)));
    }

    public static PathContext ctx(String cwd) {
        return PathContext.of(cwd);
    }

    /** {root, 排好序的只读子路径}，按 root 排序。 */
    public static List<List<Object>> sortedRoots(List<WritableRoot> roots) {
        List<WritableRoot> rs = new ArrayList<>(roots);
        rs.sort(Comparator.comparing(WritableRoot::root));
        List<List<Object>> out = new ArrayList<>();
        for (WritableRoot r : rs) {
            List<String> subs = new ArrayList<>(r.readOnlySubpaths());
            Collections.sort(subs);
            out.add(List.of(r.root(), subs));
        }
        return out;
    }

    public static boolean linux() {
        return Platform.isLinux();
    }

    public static <T> T english(Supplier<T> body) {
        return Messages.withLocale(Locale.ENGLISH, body);
    }

    /** 求值；抛了 ⇒ 异常，没抛 ⇒ null。 */
    public static Throwable thrown(ThrowingRunnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static String code(Throwable t) {
        return t instanceof SandboxException s ? s.code() : null;
    }

    public static ErrorKind kind(Throwable t) {
        return t instanceof SandboxException s ? s.kind() : null;
    }

    // ── 假件 ─────────────────────────────────────────────────────────

    /** 立即结束的假进程。 */
    public static final class FakeProcess implements ExecProcess {
        private final int exit;
        private final Deque<OutputChunk> chunks;

        public FakeProcess(int exit, List<OutputChunk> chunks) {
            this.exit = exit;
            this.chunks = new ArrayDeque<>(chunks);
        }

        @Override
        public long pid() {
            return 4242;
        }

        @Override
        public Integer exitCode() {
            return exit;
        }

        @Override
        public synchronized OutputChunk nextOutput() {
            return chunks.poll();
        }

        @Override
        public void writeInput(byte[] data) {
        }

        @Override
        public void closeInput() {
        }

        @Override
        public int waitForExit() {
            return exit;
        }

        @Override
        public Integer waitForExit(Duration timeout) {
            return exit;
        }

        @Override
        public boolean tryInterrupt() {
            return false;
        }

        @Override
        public void kill() {
        }

        @Override
        public void close() {
        }
    }

    /** 假后端：记下请求，起一个立即结束的假进程。 */
    public static final class FakeBackend implements ExecBackend {
        private final String name;
        private final int exitCode;
        private final String stderr;
        public final List<ExecRequest> requests = Collections.synchronizedList(new ArrayList<>());

        public FakeBackend(String name) {
            this(name, 0, "");
        }

        public FakeBackend(String name, int exitCode, String stderr) {
            this.name = name;
            this.exitCode = exitCode;
            this.stderr = stderr;
        }

        @Override
        public ExecCapabilities capabilities() {
            return new ExecCapabilities(name, false, false);
        }

        @Override
        public ExecProcess start(ExecRequest request) {
            requests.add(request);
            return new FakeProcess(exitCode, stderr.isEmpty() ? List.of()
                    : List.of(new OutputChunk(OutputChunk.Stream.STDERR, stderr.getBytes(StandardCharsets.UTF_8))));
        }
    }

    /** 假沙盒：probe 回给定能力；wrap 回一个「沙盒里」的假后端并记下策略。 */
    public static final class FakeSandbox implements Sandbox {
        private final SandboxCapabilities caps;
        public final ExecBackend sandboxed;
        public final List<SandboxPolicy> wrapped = Collections.synchronizedList(new ArrayList<>());

        public FakeSandbox(SandboxCapabilities caps) {
            this(caps, new FakeBackend("fake-sandboxed", 1, "Operation not permitted"));
        }

        public FakeSandbox(SandboxCapabilities caps, ExecBackend sandboxed) {
            this.caps = caps;
            this.sandboxed = sandboxed;
        }

        @Override
        public SandboxCapabilities probe() {
            return caps;
        }

        @Override
        public ExecBackend wrap(ExecBackend inner, SandboxPolicy policy, PathContext context) {
            wrapped.add(policy);
            return sandboxed;
        }
    }

    /** 假审批者：按队列回决定（用完 ⇒ DENY），记下请求。 */
    public static final class FakeApprover implements Approver {
        private final Deque<ApprovalDecision> queue;
        public final List<ApprovalRequest> requests = Collections.synchronizedList(new ArrayList<>());

        public FakeApprover(ApprovalDecision... decisions) {
            this.queue = new ArrayDeque<>(Arrays.asList(decisions));
        }

        @Override
        public synchronized ApprovalDecision decide(ApprovalRequest request) {
            requests.add(request);
            ApprovalDecision d = queue.poll();
            return d == null ? ApprovalDecision.DENY : d;
        }
    }

    /** 经 backend 跑 {@code /bin/sh -c script}（工作目录 cwd）。 */
    public static ExecResult sh(ExecBackend backend, String cwd, String script) throws IOException, InterruptedException {
        return Exec.run(backend, ExecRequest.of(List.of("/bin/sh", "-c", script), cwd));
    }

    public static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String l : String.valueOf(s).split("\n")) if (!l.isEmpty()) out.add(l);
        return out;
    }
}
