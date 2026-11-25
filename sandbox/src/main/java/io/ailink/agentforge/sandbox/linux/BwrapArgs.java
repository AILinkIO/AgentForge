package io.ailink.agentforge.sandbox.linux;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.SandboxException;
import io.ailink.agentforge.sandbox.policy.Glob;
import io.ailink.agentforge.sandbox.policy.LexicalPaths;
import io.ailink.agentforge.sandbox.policy.NetworkMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PolicyMatcher;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;
import io.ailink.agentforge.sandbox.policy.WritableRoot;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * 策略 ⇒ bwrap 参数（不含 argv[0]）。移植自 Codex codex-rs/linux-sandbox/src/bwrap.rs（Apache-2.0）：create_bwrap_command_args /
 * create_filesystem_args / append_read_only_subpath_args / append_unreadable_root_args / canonical_target_if_symlinked_path /
 * first_writable_symlink_component_in_path / expand_unreadable_globs。
 * 不借：WSL 遮罩、守护进程套接字遮罩、{@code --argv0}、{@code --ro-bind-data} 的 fd（JVM 起进程传不了额外 fd ⇒ 文件遮罩改为
 * ro-bind 一个 000 权限的空文件）。
 */
public final class BwrapArgs {
    private BwrapArgs() {
    }

    /** Linux 的平台缺省可读目录（{@code MINIMAL}）。 */
    public static final List<String> LINUX_PLATFORM_DEFAULT_READ_ROOTS =
            List.of("/bin", "/sbin", "/usr", "/etc", "/lib", "/lib64", "/nix/store", "/run/current-system/sw");

    public static final int MAX_UNREADABLE_GLOB_MATCHES = 8192;

    /** 原生件在沙盒里的固定挂载点（/dev 由 bwrap 先准备好）。 */
    public static final String PROXY_BRIDGE_LIBRARY_TARGET = "/dev/libagentforge_sandbox_native.so";

    /**
     * 选项。
     *
     * @param mountProc                   挂 /proc
     * @param inheritPid                  不开 pid namespace
     * @param network                     网络模式（不是 ENABLED ⇒ {@code --unshare-net}）
     * @param maskFile                    遮文件用的 000 空文件（没有 ⇒ 遇到要遮的文件失败即拒）
     * @param protectMissingMetadataNames 不存在的受保护名挂成只读空目录（防沙盒里建出 .git）
     * @param synthetic                   这个路径是本进程的合成挂载点（还有沙盒在用 ⇒ 当作不存在）
     * @param proxyBridgeSocket           代理桥控制 socket（只读绑进沙盒同一路径）
     * @param proxyBridgeLibrary          原生件（只读绑到 {@link #PROXY_BRIDGE_LIBRARY_TARGET}）
     */
    public record Options(boolean mountProc, boolean inheritPid, NetworkMode network, String maskFile, boolean protectMissingMetadataNames,
                          Predicate<String> synthetic, String proxyBridgeSocket, String proxyBridgeLibrary) {
        public static final Options DEFAULT = new Options(true, false, NetworkMode.RESTRICTED, null, true, null, null, null);

        public Options {
            if (network == null) network = NetworkMode.RESTRICTED;
        }

        public Options withMountProc(boolean v) {
            return new Options(v, inheritPid, network, maskFile, protectMissingMetadataNames, synthetic, proxyBridgeSocket, proxyBridgeLibrary);
        }

        public Options withInheritPid(boolean v) {
            return new Options(mountProc, v, network, maskFile, protectMissingMetadataNames, synthetic, proxyBridgeSocket, proxyBridgeLibrary);
        }

        public Options withNetwork(NetworkMode v) {
            return new Options(mountProc, inheritPid, v, maskFile, protectMissingMetadataNames, synthetic, proxyBridgeSocket, proxyBridgeLibrary);
        }

        public Options withMaskFile(String v) {
            return new Options(mountProc, inheritPid, network, v, protectMissingMetadataNames, synthetic, proxyBridgeSocket, proxyBridgeLibrary);
        }

        public Options withProtectMissingMetadataNames(boolean v) {
            return new Options(mountProc, inheritPid, network, maskFile, v, synthetic, proxyBridgeSocket, proxyBridgeLibrary);
        }

        public Options withSynthetic(Predicate<String> v) {
            return new Options(mountProc, inheritPid, network, maskFile, protectMissingMetadataNames, v, proxyBridgeSocket, proxyBridgeLibrary);
        }

        public Options withProxyBridge(String socket, String library) {
            return new Options(mountProc, inheritPid, network, maskFile, protectMissingMetadataNames, synthetic, socket, library);
        }
    }

    /** 结果：参数与本次新建的合成挂载点（进程退出后要清掉的空目录）。 */
    public record Built(List<String> args, List<String> syntheticTargets) {
        public Built {
            args = List.copyOf(args);
            syntheticTargets = List.copyOf(syntheticTargets);
        }
    }

    private static boolean unshareNetwork(Options o) {
        return o.network() != NetworkMode.ENABLED;
    }

    private static List<String> byDepth(Collection<String> paths) {
        List<String> l = new ArrayList<>(paths);
        l.sort(Comparator.comparingInt(LexicalPaths::depth)); // List.sort 稳定
        return l;
    }

    private static List<String> prefixes(String path) {
        String current = "";
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                String d = LexicalPaths.directoryName(current);
                String c = d == null ? "" : d;
                current = c.equals("/") ? "" : c;
            } else {
                current = current + "/" + part;
                out.add(current);
            }
        }
        return out;
    }

    /** 某一级是符号链接 ⇒ 全解后的真实路径（与原路径不同才回）；否则 {@code null}。 */
    public static String canonicalTargetIfSymlinked(String path) {
        for (String prefix : prefixes(path)) {
            LinuxFs.Stat st = LinuxFs.stat(prefix, false);
            if (st == null) return null;
            if (st.isSymlink()) {
                String t = LinuxFs.realPath(path);
                return t != null && !t.equals(path) ? t : null;
            }
        }
        return null;
    }

    /** 路径上第一个落在可写根之内的符号链接（沙盒里能改它 ⇒ 按它当前目标挂 / 遮都是竞态）。 */
    public static String firstWritableSymlinkComponent(String path, List<String> allowed) {
        for (String prefix : prefixes(path)) {
            LinuxFs.Stat st = LinuxFs.stat(prefix, false);
            if (st == null) return null;
            if (st.isSymlink() && allowed.stream().anyMatch(a -> LexicalPaths.isUnder(prefix, a))) return prefix;
        }
        return null;
    }

    /** 第一个通配符之前的目录（Codex split_pattern_for_ripgrep）；前缀为空或就是 / ⇒ {@code null}。 */
    public static String searchRoot(String pattern) {
        int firstWild = -1;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == ']') {
                firstWild = i;
                break;
            }
        }
        if (firstWild < 0) return LexicalPaths.directoryName(pattern);
        String prefix = pattern.substring(0, firstWild);
        if (prefix.isEmpty() || prefix.equals("/")) return null;
        int end = prefix.endsWith("/") ? prefix.length() - 1 : prefix.lastIndexOf('/');
        return end > 0 ? pattern.substring(0, end) : null;
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

    private static SandboxException fail(String code, Object... args) {
        return SandboxException.of(ErrorKind.INVALID_OPERATION, code, args);
    }

    /**
     * deny glob 展开成现有的匹配（bwrap 只能遮具体路径；这里用遍历 = Codex 的 glob_files 回落）：按第一个通配符之前的目录起扫
     * （⛔ 从根扫 ⇒ 失败即拒），匹配文件与符号链接，不跟进链接目录；符号链接再补真实目标；超过 8192 个 ⇒ 拒。
     */
    public static List<String> expandUnreadableGlobs(List<String> patterns) {
        TreeSet<String> found = new TreeSet<>();
        for (String pattern : patterns) {
            String root = searchRoot(pattern);
            if (root == null) throw fail("sandbox/bwrap-glob-too-broad", pattern);
            if (!Files.isDirectory(Path.of(root))) continue;
            Glob g = Glob.compile(pattern, false);
            if (g == null) throw fail("sandbox/bwrap-glob-too-broad", pattern);
            walk(root, g, pattern, found);
        }
        return new ArrayList<>(found);
    }

    private static void walk(String dir, Glob g, String pattern, TreeSet<String> found) {
        for (String path : listDir(dir)) {
            LinuxFs.Stat st = LinuxFs.stat(path, false);
            if (st == null) continue;
            if ((st.isRegularFile() || st.isSymlink()) && g.matches(path)) {
                found.add(path);
                String real = canonicalTargetIfSymlinked(path);
                if (real != null) found.add(real);
                if (found.size() > MAX_UNREADABLE_GLOB_MATCHES)
                    throw fail("sandbox/bwrap-glob-too-many", pattern, MAX_UNREADABLE_GLOB_MATCHES);
            }
            if (st.isDirectory()) walk(path, g, pattern, found);
        }
    }

    /** 在 anchor（已遮）与挂载目标之间补 {@code --dir}（目标是文件 ⇒ 补到它的父目录）。 */
    private static List<String> mountTargetParentDirs(String mountTarget, String anchor) {
        String dir = Files.isDirectory(Path.of(mountTarget)) ? mountTarget : LexicalPaths.directoryName(mountTarget);
        List<String> chain = new ArrayList<>();
        for (String d = dir; d != null && !LexicalPaths.same(d, anchor); d = LexicalPaths.directoryName(d)) chain.add(d);
        List<String> out = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            out.add("--dir");
            out.add(chain.get(i));
        }
        return out;
    }

    private static List<String> remap(List<String> paths, String root, String target) {
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            if (!LexicalPaths.isUnder(p, root)) out.add(p);
            else if (LexicalPaths.same(p, root)) out.add(target);
            else out.add(LexicalPaths.join(target, LexicalPaths.relative(root, p)));
        }
        return out;
    }

    private static boolean directChildOfAllowed(String path, List<String> allowed) {
        String parent = LexicalPaths.directoryName(path);
        return parent != null && LexicalPaths.containsPath(allowed, parent);
    }

    /** 遮一个不可读路径（目录：tmpfs 000 / 111；文件：ro-bind 遮罩文件）。 */
    private static List<String> unreadableArgs(String path, List<String> allowed, Options opts) {
        String link = firstWritableSymlinkComponent(path, allowed);
        if (link != null) throw fail("sandbox/bwrap-deny-symlink", path, link);
        LinuxFs.Stat st = LinuxFs.stat(path, true);
        if (st == null) return List.of();
        List<String> out = new ArrayList<>();
        if (st.isDirectory()) {
            List<String> descendants = byDepth(allowed.stream().filter(a -> !a.equals(path) && LexicalPaths.isUnder(a, path)).toList());
            out.addAll(List.of("--perms", descendants.isEmpty() ? "000" : "111", "--tmpfs", path));
            for (String d : descendants) out.addAll(mountTargetParentDirs(d, path));
            out.addAll(List.of("--remount-ro", path));
            return out;
        }
        if (opts.maskFile() == null) throw fail("sandbox/bwrap-cannot-mask", path);
        return List.of("--ro-bind", opts.maskFile(), path);
    }

    private record SubArgs(List<String> args, String synthetic) {
    }

    private static SubArgs readOnlySubpathArgs(String sub, List<String> allowed, Options opts, List<String> protectedNames) {
        String name = LexicalPaths.fileName(sub);
        boolean metadata = opts.protectMissingMetadataNames() && LexicalPaths.containsPath(protectedNames, name)
                && directChildOfAllowed(sub, allowed);
        boolean synthetic = opts.synthetic() != null && opts.synthetic().test(sub);
        if (!LinuxFs.lexists(sub) || synthetic) {
            // 不存在的受保护名：挂成只读空目录（bwrap 会在宿主上建出挂载点，跑完清掉）；父目录写不进 ⇒ 沙盒里也建不出来，不用遮
            String parent = LexicalPaths.directoryName(sub);
            if (metadata && !name.isEmpty() && parent != null) {
                LinuxFs.Stat st = LinuxFs.stat(parent, true);
                if (st != null && st.isDirectory() && LinuxFs.accessEffective(parent, LinuxFs.W_OK) == 0)
                    return new SubArgs(List.of("--perms", "555", "--tmpfs", sub, "--remount-ro", sub), sub);
            }
            return new SubArgs(List.of(), null);
        }
        boolean under = allowed.stream().anyMatch(a -> LexicalPaths.isUnder(sub, a));
        return new SubArgs(under ? List.of("--ro-bind", sub, sub) : List.of(), null);
    }

    /**
     * 文件系统挂载（次序要紧）：① 全盘可读 ⇒ {@code --ro-bind / /}，否则 {@code --tmpfs /} + 逐个只读挂可读根；② {@code --dev /dev}；
     * ③ 先遮住可写根的不可读祖先；④ 可写根按深度 {@code --bind}；⑤ 根下的只读子路径 / 受保护名 {@code --ro-bind}；⑥ 根下的不可读路径；
     * ⑦ 其余不可读路径。
     */
    public static Built filesystemArgs(SandboxPolicy pol, PathContext ctx, Options opts) {
        PolicyMatcher m = pol.matcher(ctx);
        List<String> globs = m.unreadableGlobs();
        List<String> protectedNames = pol.allProtectedNames();
        List<WritableRoot> writable = new ArrayList<>(m.writableRoots().stream().filter(w -> LinuxFs.lexists(w.root())).toList());
        if (writable.isEmpty() && pol.fullDiskWriteAccess() && !globs.isEmpty()) writable.add(new WritableRoot("/", List.of(), List.of()));
        TreeSet<String> unreadable = new TreeSet<>(m.unreadableRoots());
        unreadable.addAll(expandUnreadableGlobs(globs));
        List<String> args = new ArrayList<>();
        List<String> synthetic = new ArrayList<>();

        if (pol.fullDiskReadAccess()) {
            args.addAll(List.of("--ro-bind", "/", "/", "--dev", "/dev"));
        } else {
            TreeSet<String> readable = new TreeSet<>(m.readableRoots());
            if (pol.includePlatformDefaults()) {
                for (String r : LINUX_PLATFORM_DEFAULT_READ_ROOTS) if (LinuxFs.lexists(r)) readable.add(r);
                if (opts.inheritPid()) readable.add("/proc");
            }
            if (readable.contains("/")) {
                args.addAll(List.of("--ro-bind", "/", "/", "--dev", "/dev"));
            } else {
                args.addAll(List.of("--tmpfs", "/", "--dev", "/dev"));
                for (String root : readable) {
                    if (!LinuxFs.lexists(root)) continue;
                    // 可写根之下的只读根照可写根那样挂真实目标；纯只读根保持逻辑路径
                    String mount = null;
                    if (writable.stream().anyMatch(w -> LexicalPaths.isUnder(root, w.root()))) mount = canonicalTargetIfSymlinked(root);
                    if (mount == null) mount = root;
                    args.addAll(List.of("--ro-bind", mount, mount));
                }
            }
        }

        List<String> allowed = new ArrayList<>();
        for (WritableRoot w : writable) {
            allowed.add(w.root());
            String t = canonicalTargetIfSymlinked(w.root());
            if (t != null) allowed.add(t);
        }
        List<WritableRoot> sorted = new ArrayList<>(writable);
        sorted.sort(Comparator.comparingInt(w -> LexicalPaths.depth(w.root())));
        boolean bindsFsRoot = sorted.stream().anyMatch(w -> w.root().equals("/"));

        // 只先遮「在所有可写根之外、又是某个可写根祖先」的不可读路径
        for (String u : byDepth(unreadable.stream()
                .filter(u -> allowed.stream().noneMatch(a -> LexicalPaths.isUnder(u, a)) && allowed.stream().anyMatch(a -> LexicalPaths.isUnder(a, u)))
                .toList()))
            args.addAll(unreadableArgs(u, allowed, opts));

        for (WritableRoot w : sorted) {
            String root = w.root();
            String target = canonicalTargetIfSymlinked(root);
            // 祖先已被遮 ⇒ 先在遮罩里补出挂载目标的各级目录（同深度取最先出现的）
            String masking = null;
            for (String u : unreadable)
                if (LexicalPaths.isUnder(root, u) && (masking == null || LexicalPaths.depth(u) > LexicalPaths.depth(masking))) masking = u;
            if (masking != null) args.addAll(mountTargetParentDirs(root, masking));
            String mount = target != null ? target : root;
            if (!(bindsFsRoot && !root.equals("/") && mount.equals("/"))) {
                args.addAll(List.of("--bind", mount, mount));
                // 根 bind 盖住了先前的 /dev 且带 nodev：再挂一次标准设备
                if (mount.equals("/")) args.addAll(List.of("--dev", "/dev", "--bind-try", "/dev/shm", "/dev/shm"));
            }
            List<String> ro = new ArrayList<>(w.readOnlySubpaths().stream().filter(s -> !unreadable.contains(s)).toList());
            // 属于更深一层可写根的只读子路径：照样校验，挂载留给那个根
            List<String> deferred = ro.stream().filter(sp -> sorted.stream().anyMatch(n -> !n.root().equals(root)
                    && LexicalPaths.isUnder(n.root(), root) && LexicalPaths.isUnder(sp, n.root())
                    && LexicalPaths.containsPath(n.readOnlySubpaths(), sp))).toList();
            for (String name : w.protectedMetadataNames()) {
                String pp = LexicalPaths.join(root, name);
                if (!LexicalPaths.containsPath(ro, pp)) ro.add(pp);
            }
            if (target != null) {
                ro = remap(ro, root, target);
                deferred = remap(deferred, root, target);
            }
            for (String sub : byDepth(ro)) {
                String link = firstWritableSymlinkComponent(sub, allowed);
                if (link != null) throw fail("sandbox/bwrap-read-only-symlink", sub, link);
                if (!LexicalPaths.containsPath(deferred, sub)) {
                    SubArgs s = readOnlySubpathArgs(sub, allowed, opts, protectedNames);
                    args.addAll(s.args());
                    if (s.synthetic() != null) synthetic.add(s.synthetic());
                }
            }
            List<String> nested = unreadable.stream().filter(u -> LexicalPaths.isUnder(u, root)).toList();
            if (target != null) nested = remap(nested, root, target);
            for (String u : byDepth(nested)) args.addAll(unreadableArgs(u, allowed, opts));
        }
        for (String u : byDepth(unreadable.stream()
                .filter(u -> allowed.stream().noneMatch(a -> LexicalPaths.isUnder(u, a) || LexicalPaths.isUnder(a, u))).toList()))
            args.addAll(unreadableArgs(u, allowed, opts));
        return new Built(args, synthetic);
    }

    private static List<String> bridgeArgs(Options o) {
        List<String> out = new ArrayList<>();
        if (o.proxyBridgeSocket() != null) out.addAll(List.of("--ro-bind", o.proxyBridgeSocket(), o.proxyBridgeSocket()));
        if (o.proxyBridgeLibrary() != null) out.addAll(List.of("--ro-bind", o.proxyBridgeLibrary(), PROXY_BRIDGE_LIBRARY_TARGET));
        return out;
    }

    private static Built fullFilesystem(List<String> command, Options o) {
        List<String> a = new ArrayList<>(List.of("--new-session", "--die-with-parent", "--bind", "/", "/", "--dev", "/dev",
                "--bind-try", "/dev/shm", "/dev/shm", "--unshare-user", "--unshare-ipc"));
        if (!o.inheritPid()) a.add("--unshare-pid");
        if (unshareNetwork(o)) a.add("--unshare-net");
        if (o.mountProc()) a.addAll(List.of("--proc", "/proc"));
        a.addAll(bridgeArgs(o));
        a.addAll(List.of("--cap-drop", "ALL", "--"));
        a.addAll(command);
        return new Built(a, List.of());
    }

    /**
     * 完整参数：全盘可写且没有 deny glob ⇒ 网络放行时原样返回命令（不包），否则只隔网络；其余 ⇒ 受限文件系统。
     */
    public static Built build(List<String> command, SandboxPolicy pol, PathContext ctx, String commandCwd, Options opts) {
        List<String> globs = pol.matcher(ctx).unreadableGlobs();
        if (pol.fullDiskWriteAccess() && globs.isEmpty())
            return unshareNetwork(opts) ? fullFilesystem(command, opts) : new Built(command, List.of());
        Built fs = filesystemArgs(pol, ctx, opts);
        String real = LinuxFs.realPath(commandCwd);
        List<String> a = new ArrayList<>(List.of("--new-session", "--die-with-parent"));
        a.addAll(fs.args());
        // 显式要 user namespace（调用方是 uid 0 时 bwrap 不会自动开）
        a.add("--unshare-user");
        if (!opts.inheritPid()) a.add("--unshare-pid");
        a.add("--unshare-ipc");
        if (unshareNetwork(opts)) a.add("--unshare-net");
        if (opts.mountProc()) a.addAll(List.of("--proc", "/proc"));
        a.addAll(bridgeArgs(opts));
        // 进入规范化后的命令目录：沙盒里只挂真实路径
        a.addAll(List.of("--chdir", real != null ? real : commandCwd));
        a.addAll(List.of("--cap-drop", "ALL", "--"));
        a.addAll(command);
        return new Built(a, fs.syntheticTargets());
    }
}
