package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具的两道闸：路径限定在根下 + 输出有上限且截断要说（对应 coxswain {@code tools/limit.ss}）。
 *
 * <p>不限路径，"读文件"就是 {@code cat ~/.ssh/id_rsa}；悄悄截断比截断本身更糟 —— 模型会对着半个文件推理，而且非常自信。
 */
public final class Limit {
    private Limit() { }

    /** search / find 等共用的输出上限（三处各写一个常量，调大一个另外两个还在旧值上）。 */
    public static final int OUTPUT_LIMIT = 16000;
    /** shell 工具单独的输出上限（输出更大、stdout/stderr 分开）。 */
    public static final int SHELL_OUTPUT_LIMIT = 30000;

    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    // ── 路径限定 ──────────────────────────────────────────────────────

    private static boolean sep(char c) { return c == '/' || c == '\\'; }

    private static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || sep(path.charAt(i))) {
                if (i > start) out.add(path.substring(start, i));
                start = i + 1;
            }
        }
        return out;
    }

    static boolean absolute(String path) {
        return !path.isEmpty() && (sep(path.charAt(0)) || (path.length() >= 2 && path.charAt(1) == ':'));
    }

    /** {@code .} 丢掉、{@code ..} 弹一层 —— 在字符串上做：要在碰盘之前就知道它想去哪儿。 */
    private static List<String> normalize(List<String> parts) {
        List<String> acc = new ArrayList<>();
        for (String p : parts) {
            if (p.equals(".")) continue;
            if (p.equals("..")) {
                if (!acc.isEmpty()) acc.removeLast();
            } else acc.add(p);
        }
        return acc;
    }

    private static String join(List<String> parts, boolean rooted) {
        String body = String.join("/", parts);
        if (body.isEmpty()) return body;
        if (!WINDOWS) return "/" + body;        // POSIX：绝对路径永远保住开头那个斜杠
        return rooted ? "/" + body : body;       // Windows：只有"从根起、没盘符"的那种补回来
    }

    private static boolean rootedNoDrive(String p) { return !p.isEmpty() && sep(p.charAt(0)); }

    private static String fold(String s) { return WINDOWS ? s.toLowerCase(Locale.ROOT) : s; }

    private static boolean under(String child, String root) {
        String c = fold(child), r = fold(root);
        if (r.equals("/") && c.startsWith("/")) return true;
        return c.equals(r) || (c.length() > r.length() && c.startsWith(r) && sep(c.charAt(r.length())));
    }

    private static String want(String rootDir, String path) {
        List<String> rootParts = normalize(split(rootDir));
        boolean rootRooted = rootedNoDrive(rootDir);
        if (absolute(path)) return join(normalize(split(path)), rootedNoDrive(path));
        List<String> all = new ArrayList<>(rootParts);
        all.addAll(split(path));
        return join(normalize(all), rootRooted);
    }

    private static String rootString(String rootDir) {
        String r = join(normalize(split(rootDir)), rootedNoDrive(rootDir));
        return r.isEmpty() ? "/" : r;
    }

    /** 解析到工作根下的完整路径；越界抛给模型看的错误。缓冲区地址原样放行。 */
    public static String resolveInRoot(Workspace ws, String path) {
        if (Vfs.isBufferAddress(path)) return path;
        return resolveUnder(ws.root().toString(), path);
    }

    /** 同上，回 {@link Path}（不接受缓冲区地址）。 */
    public static Path resolvePath(Workspace ws, String path) { return Path.of(resolveUnder(ws.root().toString(), path)); }

    /**
     * 同一套限定，根由调用方给（memory 工具往配置目录下另一个根里写）。两个根各自封闭，
     * 比一个"有两个出口"的根安全得多。
     */
    public static String resolveUnder(String rootDir, String path) {
        String root = rootString(rootDir);
        String w = want(rootDir, path);
        if (!under(w, root)) throw new ToolException("路径越出了根目录 " + ToolText.q(root) + "：" + ToolText.q(path));
        // 再用 realpath 校一遍：纯字符串看不见符号链接，root 里放一个指向 /etc 的软链就能绕过去。
        // ⚠️ 不存在的路径取**最深的已存在祖先**来校（{@code link/新文件} 也要挡住）；悬空软链读它的目标判。
        Path rp = Path.of(root);
        String realRoot = Files.exists(rp) ? realpath(rp) : null;
        if (realRoot == null) return w;     // 根都不在：没有可比的真实路径，交给后面的"不存在"去报
        Path probe = Path.of(w);
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) probe = probe.getParent();
        if (probe != null) {
            String real = realpath(probe);
            if (real == null && Files.isSymbolicLink(probe)) {
                try {
                    Path t = Files.readSymbolicLink(probe);
                    real = (probe.getParent() == null ? t : probe.getParent().resolve(t)).normalize().toString();
                } catch (IOException ignored) { }
            }
            if (real != null && !under(real, realRoot)) {
                throw new ToolException(ToolText.q(path) + " 是指向根目录外面的链接（实际指向 " + ToolText.q(real) + "）");
            }
        }
        return w;
    }

    /** 同上，根与结果都是 {@link Path}。 */
    public static Path resolveUnder(Path root, String path) {
        return Path.of(resolveUnder(root.toAbsolutePath().toString(), path));
    }

    /** {@link #pathContains(String, String)} 的 Path 版。 */
    public static boolean pathContains(Path root, String path) {
        return pathContains(root.toAbsolutePath().toString(), path);
    }

    /** root 下的相对路径（{@code /} 分隔；根本身是 ""）。 */
    public static String rel(Path root, Path p) {
        return root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** resolveUnder 的纯字符串那一半：不碰盘、不抛（删除风险分类器要的正是"还没碰盘时的答案"）。 */
    public static boolean pathContains(String rootDir, String path) {
        return under(want(rootDir, path), rootString(rootDir));
    }

    /** realpath；解不出来回 null。 */
    public static String realpath(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    // ── 输出上限 ──────────────────────────────────────────────────────

    /** 截断了一定要说。 */
    public static String truncateNote(String text, int limit, String what) {
        if (text.length() <= limit) return text;
        return cut(text, limit) + "\n\n[已截断：" + what + " 共 " + text.length() + " 个字符，上面是前 " + limit + " 个]";
    }

    /** 截前 limit 个字符；不劈开代理对。 */
    static String cut(String s, int limit) {
        int n = Math.min(limit, s.length());
        if (n > 0 && n < s.length() && Character.isHighSurrogate(s.charAt(n - 1))) n--;
        return s.substring(0, n);
    }

    private static volatile Path spillDir;
    private static final AtomicLong SPILL_SEQ = new AtomicLong();
    private static final long SPILL_RETENTION_MS = 7L * 24 * 3600 * 1000;

    /**
     * 测试 / 宿主改落盘目录；null = {@code AgentPaths.dataDir()/spill}（数据目录，不进项目 —— 免得污染 git status；
     * 也不放系统临时目录 —— 那里多半是 tmpfs，大输出落进去占的是内存）。
     */
    public static void spillDir(Path dir) { spillDir = dir; }

    public static Path spillDir() {
        Path d = spillDir;
        return d != null ? d : AgentPaths.dataDir().resolve("spill");
    }

    /**
     * 截断了还把完整的那份落盘、告诉模型在哪。只给"再拿一遍要付真代价"的输出用（run-command / web-fetch）。
     * 落盘失败退回普通截断注记。
     */
    public static String truncateNoteSpill(String text, int limit, String what) {
        if (text.length() <= limit) return text;
        Path p = spill(text);
        if (p == null) return truncateNote(text, limit, what);
        return cut(text, limit) + "\n\n[已截断：" + what + " 共 " + text.length() + " 个字符，上面是前 " + limit
                + " 个。完整内容已存到 " + p + " —— 用 run-command 的 grep / sed -n / tail 看需要的那段，别重跑命令]";
    }

    private static Path spill(String text) {
        try {
            Path dir = spillDir();
            Files.createDirectories(dir);
            try { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")); } catch (Exception ignored) { }
            purge(dir);
            // 序号原子递增：两条命令同时截断时不许写到同一个路径
            Path p = dir.resolve("tool-" + ProcessHandle.current().pid() + "-" + SPILL_SEQ.incrementAndGet() + ".txt");
            Files.writeString(p, text, StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) { }
            return p;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void purge(Path dir) {
        long cutoff = System.currentTimeMillis() - SPILL_RETENTION_MS;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "tool-*")) {
            for (Path p : ds) {
                try {
                    if (Files.isRegularFile(p) && Files.getLastModifiedTime(p).toMillis() < cutoff) Files.delete(p);
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    // ── 存在性 ────────────────────────────────────────────────────────

    public enum Want { FILE, DIR }

    /**
     * "文件不存在"是模型最常犯、也最容易自己改对的错 —— 前提是报错让它知道改什么：分清"是目录"与"不存在"，
     * 并给出同目录下相近的名字。在不在问端口（刚写进 staging 的文件盘上还没有）。
     */
    public static String mustExist(Workspace ws, String shown, String full, Want want) {
        if (!Vfs.exists(ws, full)) {
            if (Vfs.isBufferAddress(full)) {
                throw new ToolException("没有叫 " + ToolText.q(Vfs.bufferName(full)) + " 的缓冲区（用 list-buffers 看现在开着哪些）");
            }
            throw new ToolException((want == Want.DIR ? "目录" : "文件") + "不存在：" + ToolText.q(shown) + didYouMean(shown, full));
        }
        Path p = Path.of(full);
        if (!Files.exists(p)) {
            // 端口说在、盘上没有 ⇒ 只攒在端口里，而端口攒的一定是文件
            if (want == Want.DIR) throw new ToolException(ToolText.q(shown) + " 不是目录，用 read-file 读它");
            return full;
        }
        boolean dir = Files.isDirectory(p);
        if (want == Want.FILE && dir) throw new ToolException(ToolText.q(shown) + " 是一个目录，用 list-dir 看它");
        if (want == Want.DIR && !dir) throw new ToolException(ToolText.q(shown) + " 不是目录，用 read-file 读它");
        return full;
    }

    private static String didYouMean(String shown, String full) {
        try {
            String dir = dirname(full);
            String base = basename(full).toLowerCase(Locale.ROOT);
            String shownDir = dirname(shown);
            if (base.isEmpty() || dir.isEmpty() || !Files.isDirectory(Path.of(dir))) return "";
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of(dir))) {
                for (Path e : ds) names.add(e.getFileName().toString());
            }
            names.sort(null);
            List<String> hits = new ArrayList<>();
            for (String nm : names) {
                if (hits.size() >= 3) break;
                String l = nm.toLowerCase(Locale.ROOT);
                if (!l.equals(base) && (l.contains(base) || base.contains(l))) hits.add(shownDir.isEmpty() ? nm : shownDir + "/" + nm);
            }
            return hits.isEmpty() ? "" : "。你是不是想找：" + String.join("、", hits);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static int lastSep(String p) {
        for (int i = p.length() - 1; i >= 0; i--) if (sep(p.charAt(i))) return i;
        return -1;
    }

    static String basename(String p) { int i = lastSep(p); return i < 0 ? p : p.substring(i + 1); }

    static String dirname(String p) {
        int i = lastSep(p);
        if (i < 0) return "";
        if (i == 0) return p.substring(0, 1);
        return p.substring(0, i);
    }
}
