package io.ailink.agentforge.sandbox.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 一份策略在一个上下文里解析好的条目与 deny 匹配器（Codex resolved_entries / ReadDenyMatcher）。构造时解析一次；不可变 ⇒ 线程安全。
 */
public final class PolicyMatcher {

    /** 解析成绝对路径的条目。 */
    public record ResolvedEntry(String path, AccessMode access, int depth) {
    }

    private final SandboxPolicy policy;
    private final PathContext context;
    private final List<ResolvedEntry> entries;
    private final boolean fullWrite;
    private final List<String> protectedNames;
    private final List<String> deniedRoots;
    private final List<Glob> denyGlobs;
    private final boolean invalidGlob;

    PolicyMatcher(SandboxPolicy policy, PathContext context) {
        this.policy = policy;
        this.context = context;
        List<ResolvedEntry> es = new ArrayList<>();
        if (policy.isRestricted()) {
            for (PolicyEntry e : policy.entries())
                for (String path : resolveEntryPaths(e.path(), context))
                    es.add(new ResolvedEntry(path, e.access(), LexicalPaths.depth(path)));
        }
        this.entries = List.copyOf(es);
        this.fullWrite = policy.fullDiskWriteAccess();
        this.protectedNames = policy.allProtectedNames();
        String fsRoot = LexicalPaths.fileSystemRoot(context.cwd());
        List<String> denied = new ArrayList<>();
        for (ResolvedEntry e : entries)
            if (e.access() == AccessMode.DENY && !LexicalPaths.same(e.path(), fsRoot)) denied.add(e.path());
        this.deniedRoots = List.copyOf(denied);
        DenyGlobs dg = denyGlobPatterns(policy, context);
        List<Glob> globs = new ArrayList<>();
        boolean bad = dg.unresolved();
        for (String pat : dg.patterns()) {
            Glob g = Glob.compile(pat, LexicalPaths.WINDOWS);
            if (g == null) bad = true;
            else globs.add(g);
        }
        this.denyGlobs = List.copyOf(globs);
        this.invalidGlob = bad;
    }

    public SandboxPolicy policy() {
        return policy;
    }

    public PathContext context() {
        return context;
    }

    public List<ResolvedEntry> entries() {
        return entries;
    }

    private static List<String> resolveEntryPaths(PolicyPath path, PathContext ctx) {
        return switch (path) {
            case PolicyPath.Literal l -> List.of(LexicalPaths.resolve(l.path(), ctx.cwd()));
            case PolicyPath.GlobPattern g -> List.of();
            case PolicyPath.Special s -> switch (s.kind()) {
                case ROOT -> List.of(LexicalPaths.fileSystemRoot(ctx.cwd()));
                case PROJECT_ROOTS -> {
                    List<String> out = new ArrayList<>();
                    for (String r : ctx.effectiveProjectRoots()) {
                        String j = s.subpath() == null ? r : LexicalPaths.joinScoped(r, s.subpath());
                        if (j != null) out.add(j);
                    }
                    yield out;
                }
                case TMPDIR -> ctx.temporaryDirectories().stream().filter(LexicalPaths::isAbsolute).map(LexicalPaths::normalize).toList();
                case SLASH_TMP -> LexicalPaths.WINDOWS ? List.of() : List.of("/tmp");
                case MINIMAL -> List.of();
            };
        };
    }

    private record DenyGlobs(List<String> patterns, boolean unresolved) {
    }

    private static String trimStartSeparators(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '/' || s.charAt(i) == '\\')) i++;
        return s.substring(i);
    }

    /** deny glob 按 cwd / 家目录解析成绝对模式。 */
    private static DenyGlobs denyGlobPatterns(SandboxPolicy pol, PathContext ctx) {
        List<String> acc = new ArrayList<>();
        boolean unresolved = false;
        if (!pol.isRestricted()) return new DenyGlobs(acc, false);
        for (PolicyEntry e : pol.entries()) {
            if (e.access() != AccessMode.DENY || !(e.path() instanceof PolicyPath.GlobPattern g)) continue;
            String pat = g.pattern();
            if (pat.startsWith("~/") || (LexicalPaths.WINDOWS && pat.startsWith("~\\"))) {
                if (ctx.homeDirectory() != null) acc.add(LexicalPaths.join(ctx.homeDirectory(), trimStartSeparators(pat.substring(2))));
                else unresolved = true;
            } else {
                acc.add(LexicalPaths.isAbsolute(pat) ? pat : LexicalPaths.join(ctx.cwd(), pat));
            }
        }
        return new DenyGlobs(acc, unresolved);
    }

    /** deny 的 glob，按 cwd / 家目录解析成绝对模式、排序去重（Codex get_unreadable_globs_with_cwd）。 */
    public List<String> unreadableGlobs() {
        return new ArrayList<>(new TreeSet<>(denyGlobPatterns(policy, context).patterns()));
    }

    private String resolvePath(String path) {
        return LexicalPaths.resolve(path, context.cwd());
    }

    /** 某路径的访问级别（最具体条目；没有条目覆盖 ⇒ DENY）。不受限 ⇒ WRITE。 */
    public AccessMode resolveAccess(String path) {
        if (!policy.isRestricted()) return AccessMode.WRITE;
        String pp = resolvePath(path);
        ResolvedEntry best = null;
        for (ResolvedEntry e : entries) {
            if (LexicalPaths.isUnder(pp, e.path())
                    && (best == null || e.depth() > best.depth() || (e.depth() == best.depth() && e.access().rank() > best.access().rank())))
                best = e;
        }
        return best == null ? AccessMode.DENY : best.access();
    }

    /** 写 path 会碰到哪个受保护名（没有显式 write 条目放开它时）；不碰 ⇒ {@code null}。 */
    public String metadataWriteDenial(String path) {
        if (!policy.isRestricted()) return null;
        String pp = resolvePath(path);
        String hitPath = null;
        String hitName = null;
        outer:
        for (ResolvedEntry e : entries) {
            if (e.access() != AccessMode.WRITE) continue;
            for (String n : protectedNames) {
                String c = LexicalPaths.join(e.path(), n);
                if (LexicalPaths.isUnder(pp, c)) {
                    hitPath = c;
                    hitName = n;
                    break outer;
                }
            }
        }
        if (hitPath == null) return null;
        for (ResolvedEntry e : entries)
            if (e.access() == AccessMode.WRITE && LexicalPaths.isUnder(pp, e.path()) && LexicalPaths.isUnder(e.path(), hitPath)) return null;
        return hitName;
    }

    /** 可写：级别为 WRITE 且不碰受保护名（全盘可写时不查）。 */
    public boolean canWrite(String path) {
        String pp = resolvePath(path);
        return resolveAccess(pp) == AccessMode.WRITE && (fullWrite || metadataWriteDenial(pp) == null);
    }

    /** 被 deny 条目（路径及其下 / glob）挡住读；有解析不了的 glob ⇒ 一律拒（失败即拒）。 */
    public boolean readDenied(String path) {
        if (!policy.isRestricted()) return false;
        if (invalidGlob) return true;
        String pp = resolvePath(path);
        for (String d : deniedRoots) if (LexicalPaths.isUnder(pp, d)) return true;
        for (Glob g : denyGlobs) if (g.matches(pp)) return true;
        return false;
    }

    /** {@code .git} 是 {@code gitdir: <path>} 指针文件 ⇒ 指向的（存在的）目录；否则 {@code null}（Codex resolve_gitdir_from_file）。 */
    public static String resolveGitdir(String dotGit) {
        Path file = Path.of(dotGit);
        if (!Files.isRegularFile(file)) return null;
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        int colon = text.indexOf(':');
        if (colon < 0 || !"gitdir".equals(text.substring(0, colon).trim())) return null;
        String raw = text.substring(colon + 1).trim();
        String base = LexicalPaths.directoryName(dotGit);
        if (raw.isEmpty() || base == null) return null;
        String resolved = LexicalPaths.resolve(raw, base);
        return Files.exists(Path.of(resolved)) ? resolved : null;
    }

    private static boolean exists(String path) {
        return Files.exists(Path.of(path));
    }

    /** 可写根下缺省只读的子路径：存在的受保护名；宿主元数据名在项目根下即使不存在也算。 */
    private List<String> defaultReadOnlySubpaths(String root, boolean cwd) {
        List<String> out = new ArrayList<>();
        for (String name : policy.allProtectedNames()) {
            String path = LexicalPaths.join(root, name);
            boolean host = LexicalPaths.containsPath(policy.hostMetadataNames(), name);
            if (name.equals(".git") && Files.isRegularFile(Path.of(path))) {
                String gitdir = resolveGitdir(path);
                if (gitdir != null) out.add(gitdir);
            }
            if (exists(path) || (host && cwd)) out.add(path);
        }
        return LexicalPaths.distinct(out);
    }

    /** 可写根（Codex get_writable_roots_with_cwd）；全盘可写 ⇒ 空。 */
    public List<WritableRoot> writableRoots() {
        if (fullWrite || entries.stream().noneMatch(e -> e.access() == AccessMode.WRITE)) return List.of();
        // 只留「声明的级别与实际可写性一致」的条目
        List<ResolvedEntry> effective = new ArrayList<>();
        for (ResolvedEntry e : entries) if ((e.access() == AccessMode.WRITE) == canWrite(e.path())) effective.add(e);
        if (effective.stream().noneMatch(e -> e.access() == AccessMode.WRITE)) return List.of();
        boolean git = LexicalPaths.containsPath(protectedNames, ".git");
        List<ResolvedEntry> prepared = new ArrayList<>(effective);
        if (git) {
            for (ResolvedEntry e : effective) {
                if (e.access() != AccessMode.WRITE) continue;
                String g = resolveGitdir(LexicalPaths.join(e.path(), ".git"));
                if (g != null && entries.stream().noneMatch(x -> LexicalPaths.same(x.path(), g)))
                    prepared.add(new ResolvedEntry(g, AccessMode.READ, LexicalPaths.depth(g)));
            }
        }
        List<String> roots = LexicalPaths.distinct(prepared.stream().filter(e -> e.access() == AccessMode.WRITE).map(ResolvedEntry::path).toList());
        List<WritableRoot> out = new ArrayList<>();
        for (String root : roots) {
            List<String> names = protectedNames.stream().filter(n -> !canWrite(LexicalPaths.join(root, n))).toList();
            boolean cwd = LexicalPaths.same(root, context.cwd());
            List<String> ro = new ArrayList<>();
            for (String sp : defaultReadOnlySubpaths(root, cwd))
                if (entries.stream().noneMatch(e -> LexicalPaths.same(e.path(), sp))) ro.add(sp);
            for (ResolvedEntry e : prepared)
                if (e.access() != AccessMode.WRITE && !LexicalPaths.same(e.path(), root) && LexicalPaths.isUnder(e.path(), root)) ro.add(e.path());
            out.add(new WritableRoot(root, LexicalPaths.distinct(ro), names));
        }
        return out;
    }

    /** 显式可读根（全盘可读 ⇒ 空）。 */
    public List<String> readableRoots() {
        if (policy.fullDiskReadAccess()) return List.of();
        List<String> out = new ArrayList<>();
        for (ResolvedEntry e : entries)
            if (e.access() != AccessMode.DENY && resolveAccess(e.path()) != AccessMode.DENY) out.add(e.path());
        return LexicalPaths.distinct(out);
    }

    /** 显式不可读根（文件系统根除外）。 */
    public List<String> unreadableRoots() {
        if (!policy.isRestricted()) return List.of();
        String fsRoot = LexicalPaths.fileSystemRoot(context.cwd());
        List<String> out = new ArrayList<>();
        for (ResolvedEntry e : entries)
            if (e.access() == AccessMode.DENY && resolveAccess(e.path()) == AccessMode.DENY && !LexicalPaths.same(e.path(), fsRoot))
                out.add(e.path());
        return LexicalPaths.distinct(out);
    }
}
