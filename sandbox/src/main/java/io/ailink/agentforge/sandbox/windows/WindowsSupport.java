package io.ailink.agentforge.sandbox.windows;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.Platform;
import io.ailink.agentforge.sandbox.SandboxException;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.ffm.WinAcl;
import io.ailink.agentforge.sandbox.ffm.WinApi;
import io.ailink.agentforge.sandbox.policy.LexicalPaths;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * Windows 沙盒的平台无关部分（参照 Codex windows-sandbox-rs）：命令行引号、环境块、能力 SID、路径守卫、ACL 账本与撤销次序。
 * 原生调用在 {@code io.ailink.agentforge.sandbox.ffm.Win*}。⚠ 原生部分未在 Windows 上验证（只在 Linux 上编译 / 跑平台无关部分）。
 */
public final class WindowsSupport {
    private WindowsSupport() {
    }

    public static boolean exists(String s) {
        return Files.exists(Path.of(s));
    }

    public static boolean isDirectory(String s) {
        return Files.isDirectory(Path.of(s));
    }

    public static boolean isWindows() {
        return Platform.isWindows();
    }

    // ── 命令行（MSVCRT 引号规则，参照 Codex utils/windows-argv） ───────────

    public static final int MAX_COMMAND_LINE = 32767;

    private static void appendQuoted(StringBuilder sb, String arg) {
        if (!arg.isEmpty() && arg.indexOf(' ') < 0 && arg.indexOf('\t') < 0 && arg.indexOf('"') < 0) {
            sb.append(arg); // 无空白 / 引号 / 空串 ⇒ 不用引号
            return;
        }
        sb.append('"');
        int bs = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                bs++;
            } else if (c == '"') {
                sb.append("\\".repeat(2 * bs + 1)).append('"');
                bs = 0;
            } else {
                sb.append("\\".repeat(bs)).append(c);
                bs = 0;
            }
        }
        // 结尾反斜杠成对转义，防止把收尾引号转掉
        sb.append("\\".repeat(2 * bs)).append('"');
    }

    /** argv ⇒ CreateProcess 的命令行；拼不出（含 NUL）或超长 ⇒ {@code null}。 */
    public static String commandLine(List<String> args) {
        if (args == null || args.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null || a.indexOf('\u0000') >= 0) return null;
            if (sb.length() > 0) sb.append(' ');
            appendQuoted(sb, a);
            if (sb.length() > MAX_COMMAND_LINE) return null;
        }
        return sb.toString();
    }

    // ── 环境块 ───────────────────────────────────────────────────────

    /** 尽力断网的代理变量（一期不是隔离，能力声明里写明）。 */
    public static final Map<String, String> POISON;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("HTTP_PROXY", "http://127.0.0.1:9");
        m.put("HTTPS_PROXY", "http://127.0.0.1:9");
        m.put("ALL_PROXY", "http://127.0.0.1:9");
        m.put("NO_PROXY", "");
        m.put("GIT_HTTP_PROXY", "http://127.0.0.1:9");
        m.put("GIT_HTTPS_PROXY", "http://127.0.0.1:9");
        m.put("PIP_NO_INDEX", "1");
        m.put("NPM_CONFIG_OFFLINE", "true");
        m.put("CARGO_NET_OFFLINE", "true");
        m.put("GIT_SSH_COMMAND", "cmd /c exit 1");
        m.put("GIT_ALLOW_PROTOCOLS", "");
        POISON = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * 继承的宿主环境 + 请求的覆盖（null ⇒ 删）+ 网络投毒（仅 {@code restricted}）；{@code sandboxTemp} 非空 ⇒ TEMP / TMP 指向它。
     * ⇒ 键不分大小写的 TreeMap。
     */
    public static TreeMap<String, String> environment(ExecRequest request, boolean networkRestricted, String sandboxTemp) {
        TreeMap<String, String> env = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (request.inheritEnvironment()) env.putAll(System.getenv());
        request.environment().forEach((k, v) -> {
            if (v == null) env.remove(k);
            else env.put(k, v);
        });
        if (networkRestricted) env.putAll(POISON);
        if (sandboxTemp != null) {
            env.put("TEMP", sandboxTemp);
            env.put("TMP", sandboxTemp);
        }
        return env;
    }

    /** 按不分大小写排序、重复键留第一个拼写；双 NUL 结尾（CREATE_UNICODE_ENVIRONMENT）。 */
    public static byte[] unicodeBlock(Map<String, String> env) {
        TreeMap<String, String> seen = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        env.forEach((k, v) -> seen.putIfAbsent(k, k + "=" + v));
        return (String.join("\u0000", seen.values()) + "\u0000\u0000").getBytes(StandardCharsets.UTF_16LE);
    }

    // ── 能力 SID ─────────────────────────────────────────────────────

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 每次运行随机的 {@code S-1-5-21-{u32}-{u32}-{u32}-{u32}}（Codex cap.rs）：令牌只带本次在用根对应的 SID。 */
    public static String randomCapabilitySid() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder("S-1-5-21");
        for (int at = 0; at < 16; at += 4) {
            long v = 0;
            for (int i = 0; i < 4; i++) v |= (long) (b[at + i] & 0xFF) << (8 * i);
            sb.append('-').append(v);
        }
        return sb.toString();
    }

    // ── 路径守卫（UNC / ADS / 重解析点 / 8.3 短名） ─────────────────────────

    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;

    private static long attributes(String path) throws IOException {
        Path pp = Path.of(path);
        if (LexicalPaths.WINDOWS) {
            Object a = Files.getAttribute(pp, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
            if (a instanceof Number n) return n.longValue();
        }
        long r = 0;
        if (Files.isDirectory(pp, LinkOption.NOFOLLOW_LINKS)) r |= FILE_ATTRIBUTE_DIRECTORY;
        if (Files.isSymbolicLink(pp)) r |= FILE_ATTRIBUTE_REPARSE_POINT | (Files.isDirectory(pp) ? FILE_ATTRIBUTE_DIRECTORY : 0);
        return r;
    }

    private static SandboxException fail(String code, Object... args) {
        return SandboxException.of(ErrorKind.INVALID_OPERATION, code, args);
    }

    private static void rejectUnc(String path) {
        if (path.startsWith("\\\\") || path.startsWith("//")) throw fail("sandbox/win-path-unc-rejected", path);
    }

    /** ADS（file:stream）一律拒：ACE 打在流上与打在文件上是两回事。 */
    private static void rejectAds(String path) {
        String rest = path.startsWith("\\\\?\\") ? path.substring(4) : path;
        if (rest.length() >= 2 && rest.charAt(1) == ':') rest = rest.substring(2);
        if (rest.contains(":")) throw fail("sandbox/win-path-ads-rejected", path);
    }

    private static void rejectReparseAncestors(String path) {
        String current = path;
        while (current != null && !current.isEmpty()) {
            long a;
            try {
                a = exists(current) ? attributes(current) : 0;
            } catch (Exception e) {
                a = 0;
            }
            if ((a & FILE_ATTRIBUTE_REPARSE_POINT) != 0) throw fail("sandbox/win-path-reparse-rejected", path);
            String parent = LexicalPaths.directoryName(current);
            if (parent == null || parent.isEmpty() || parent.equalsIgnoreCase(current)) break;
            current = parent;
        }
    }

    private static String resolveFinalPath(String path) {
        if (!LexicalPaths.WINDOWS) return path;
        String text = WinApi.finalPathName(path);
        if (text == null) return path;
        if (text.startsWith("\\\\?\\UNC\\")) throw fail("sandbox/win-path-unc-rejected", path);
        return LexicalPaths.fullPath(text.startsWith("\\\\?\\") ? text.substring(4) : text);
    }

    /** 策略根（可写根 / 可读根 / deny 目标）：完整校验 + 解析真实路径。 */
    public static String canonicalizeRoot(String path) {
        rejectUnc(path);
        rejectAds(path);
        String full = LexicalPaths.fullPath(path);
        if (!exists(full)) throw fail("sandbox/win-path-missing", full);
        rejectReparseAncestors(full);
        return resolveFinalPath(full);
    }

    /** 宿主给的路径（工具链 / 可执行文件 / 运行临时目录）：拒 UNC / ADS，解析真实路径。 */
    public static String canonicalizeExisting(String path) {
        String full = LexicalPaths.fullPath(path);
        rejectUnc(full);
        rejectAds(full);
        return exists(full) ? resolveFinalPath(full) : full;
    }

    private static List<String> listDir(String dir) {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(Path.of(dir))) {
            for (Path p : s) out.add(p.toString());
        } catch (IOException | SecurityException e) {
            return List.of();
        }
        return out;
    }

    /** 枚举根下已存在的文件与目录（跳过重解析点目录，避免绕出根与死循环）。 */
    public static List<String> enumerateExistingPaths(String root) {
        Deque<String> pending = new ArrayDeque<>();
        pending.push(root);
        List<String> out = new ArrayList<>();
        while (!pending.isEmpty()) {
            String dir = pending.pop();
            for (String entry : listDir(dir)) {
                long a;
                try {
                    a = attributes(entry);
                } catch (Exception e) {
                    continue;
                }
                if ((a & FILE_ATTRIBUTE_DIRECTORY) != 0 && (a & FILE_ATTRIBUTE_REPARSE_POINT) == 0) pending.push(entry);
                out.add(entry);
            }
        }
        return out;
    }

    /** 在 root 的一层里找名字大小写不敏感匹配的现存项（.GiT 与 .git 在 NTFS 上是同一个名字）。 */
    public static String findExistingByName(String root, String name) {
        if (root == null || root.isEmpty() || name == null || name.isEmpty()) return null;
        String exact = LexicalPaths.combine(root, name);
        if (exists(exact)) return exact;
        for (String p : listDir(root)) if (LexicalPaths.fileName(p).equalsIgnoreCase(name)) return p;
        return null;
    }

    // ── ACL 账本 ─────────────────────────────────────────────────────

    public static String escape(String s) {
        return s.replace("|", "%7C");
    }

    public static String unescape(String s) {
        return s.replace("%7C", "|");
    }

    public static void appendLedger(String ledger, LedgerEntry e) throws IOException {
        Files.writeString(Path.of(ledger), e.format() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static List<LedgerEntry> readLedger(String ledger) throws IOException {
        if (!exists(ledger)) return new ArrayList<>();
        List<LedgerEntry> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(ledger), StandardCharsets.UTF_8)) {
            String[] parts = line.split("\\|", -1);
            if (parts.length != 5 && parts.length != 6) continue;
            try {
                out.add(new LedgerEntry(unescape(parts[0]), parts[1], parts[2], Integer.parseUnsignedInt(parts[3]), Integer.parseInt(parts[4]),
                        parts.length == 6 && !parts[5].isEmpty() ? unescape(parts[5]) : null));
            } catch (NumberFormatException ignored) {
                // 坏行跳过
            }
        }
        return out;
    }

    public static void writeLedger(String ledger, List<LedgerEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (LedgerEntry e : entries) sb.append(e.format()).append(System.lineSeparator());
        Files.writeString(Path.of(ledger), sb.toString(), StandardCharsets.UTF_8);
    }

    /** {@link #release} 的结果：这是不是最后一份（该撤销了）、随之交出的 DACL 快照。 */
    public record Release(List<LedgerEntry> remaining, boolean last, String snapshot) {
    }

    /** 销掉本次这一份账：还有别的持有者就只销账（并把 DACL 快照转交给剩下的持有者）。 */
    public static Release release(List<LedgerEntry> remaining, LedgerEntry entry) {
        String k = entry.key();
        int idx = -1;
        for (int i = 0; i < remaining.size(); i++) {
            LedgerEntry e = remaining.get(i);
            if (k.equals(e.key()) && java.util.Objects.equals(e.sddl(), entry.sddl())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) for (int i = 0; i < remaining.size(); i++) if (k.equals(remaining.get(i).key())) {
            idx = i;
            break;
        }
        if (idx < 0) return new Release(remaining, true, null); // 账上已经没有这条 ⇒ 视作可撤销
        LedgerEntry removed = remaining.get(idx);
        List<LedgerEntry> rest = new ArrayList<>(remaining);
        rest.remove(idx);
        if (removed.sddl() != null) {
            // 快照跟着这条 ACE 走：谁最后撤，谁负责按快照还原
            for (int j = 0; j < rest.size(); j++) {
                if (k.equals(rest.get(j).key()) && rest.get(j).sddl() == null) {
                    rest.set(j, rest.get(j).withSddl(removed.sddl()));
                    break;
                }
            }
        }
        boolean last = rest.stream().noneMatch(e -> k.equals(e.key()));
        return new Release(rest, last, removed.sddl());
    }

    public static boolean isEmptyDirectory(String dir) throws IOException {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(Path.of(dir))) {
            return !s.iterator().hasNext();
        }
    }

    /** ACE 改动的结果。 */
    public enum AceChange { CHANGED, ALREADY_PRESENT, BLOCKED }

    /** {@link WinAcl#setAce} 的 Java 面。 */
    public static AceChange setAce(String path, String sid, int mode, int mask, int inheritance) {
        return switch (WinAcl.setAce(path, sid, mode, mask, inheritance)) {
            case 0 -> AceChange.CHANGED;
            case 1 -> AceChange.ALREADY_PRESENT;
            default -> AceChange.BLOCKED;
        };
    }

    /** 按账撤销（崩溃恢复：宿主启动时调一次，须在 {@link #withLedgerLock} 里）；撤销成功一条删一条。 */
    public static void recoverLedger(String ledger) throws IOException {
        List<LedgerEntry> remaining = new ArrayList<>();
        for (LedgerEntry e : readLedger(ledger)) {
            try {
                if (e.kind().equals("synthetic")) {
                    if (isDirectory(e.path()) && isEmptyDirectory(e.path())) Files.delete(Path.of(e.path()));
                } else if (e.kind().equals("deny-read") && e.sddl() != null) {
                    WinAcl.restoreDacl(e.path(), e.sddl());
                } else {
                    setAce(e.path(), e.sid(), WinApi.REVOKE_ACCESS, 0, 0);
                }
            } catch (Exception ex) {
                if (!(e.kind().equals("synthetic") && !isDirectory(e.path()))) remaining.add(e);
            }
        }
        if (exists(ledger)) writeLedger(ledger, remaining);
    }

    private static final ConcurrentHashMap<String, Semaphore> LEDGER_GATES = new ConcurrentHashMap<>();

    /** 带 IO 的账本操作。 */
    @FunctionalInterface
    public interface LedgerAction<T> {
        T run() throws IOException;
    }

    /**
     * 账本锁：同进程内按账本路径串行 + 跨进程文件锁（{@code .lock} 上的 FileLock）。授权与记账必须原子，否则崩溃恢复会漏撤 / 误撤。
     */
    public static <T> T withLedgerLock(String ledger, LedgerAction<T> f) throws IOException {
        String k = LexicalPaths.fullPath(ledger);
        Semaphore gate = LEDGER_GATES.computeIfAbsent(LexicalPaths.key(k), x -> new Semaphore(1));
        gate.acquireUninterruptibly();
        try {
            FileChannel ch = null;
            FileLock lock = null;
            for (int attempt = 0; lock == null; attempt++) {
                if (ch == null) {
                    try {
                        ch = new RandomAccessFile(k + ".lock", "rw").getChannel();
                    } catch (IOException e) {
                        ch = null;
                    }
                }
                if (ch != null) {
                    try {
                        lock = ch.tryLock();
                    } catch (IOException | OverlappingFileLockException e) {
                        lock = null;
                    }
                }
                if (lock != null) break;
                if (attempt >= 600) {
                    if (ch != null) ch.close();
                    throw new IOException("sandbox ledger lock timeout: " + k);
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (ch != null) ch.close();
                    throw new IOException("interrupted waiting for ledger lock", e);
                }
            }
            try {
                return f.run();
            } finally {
                try {
                    lock.release();
                    ch.close();
                } catch (IOException ignored) {
                    // 收尾
                }
            }
        } finally {
            gate.release();
        }
    }

    public static void deleteTree(String dir) {
        try (Stream<Path> s = Files.walk(Path.of(dir))) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 尽力
        }
    }

    public static boolean isBatch(String program) {
        String l = program.toLowerCase(Locale.ROOT);
        return l.endsWith(".bat") || l.endsWith(".cmd");
    }

    /** Windows 后端共同的请求校验：⛔ tty、⛔ 空 argv、⛔ 未许可的批处理。⇒ 程序。 */
    public static String checkRequest(ExecRequest request) {
        if (request.tty()) throw SandboxException.of(ErrorKind.NOT_SUPPORTED, "sandbox/win-tty-unsupported");
        if (request.arguments().isEmpty()) throw SandboxException.of(ErrorKind.ARGUMENT, "sandbox/win-empty-arguments");
        String program = request.arguments().getFirst();
        if (!request.allowBatchFile() && isBatch(program)) throw SandboxException.of(ErrorKind.ARGUMENT, "sandbox/win-batch-refused", program);
        return program;
    }
}
