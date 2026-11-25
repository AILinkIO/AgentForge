package io.ailink.agentforge.agent.tools.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * worktree 机制（coxswain {@code (coxn git worktree)} + {@code (coxn worktree)}）：porcelain 解析、
 * 增删重置、默认分支探测、命名 / 落点策略。工具层的闸在 {@link WorktreeTools}。
 *
 * <p>落点：项目**旁边**的 {@code <父目录>/.agentforge-worktrees/<项目名>/<名字>}（不进项目，
 * 不污染 git status）；分支 {@code agentforge/<名字>}。删分支只删这个前缀的（双保险）。
 */
public final class GitWorktree {
    private GitWorktree() { }

    public static final String CONTAINER = ".agentforge-worktrees";
    public static final String BRANCH_PREFIX = "agentforge/";
    public static final int NAME_ATTEMPTS = 26;

    /** 机制层失败：消息是给模型看的一句话。 */
    public static final class GitException extends Exception {
        public GitException(String m) { super(m); }
    }

    public enum Kind { MAIN, LINKED }

    /** branch 为 null = detached HEAD。 */
    public record Entry(Kind kind, String dir, String branch) { }

    // ── 命名 / 落点 ─────────────────────────────────────────────────────

    public static Path worktreesRoot(Path root) {
        Path r = root.toAbsolutePath().normalize();
        Path parent = r.getParent() == null ? r : r.getParent();
        return parent.resolve(CONTAINER).resolve(r.getFileName() == null ? "root" : r.getFileName().toString());
    }

    public static Path nameToDir(Path root, String name) { return worktreesRoot(root).resolve(name); }

    public static String branchName(String name) { return BRANCH_PREFIX + name; }

    public static boolean ownBranch(String branch) { return branch != null && branch.startsWith(BRANCH_PREFIX); }

    /** 小写字母数字留下、大写转小写、其余连续段换成一个 {@code -}，两头的 {@code -} 去掉。 */
    public static String slugify(String s) {
        String t = s == null ? "" : s.strip();
        StringBuilder out = new StringBuilder();
        boolean lastDash = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) { out.append(c); lastDash = false; }
            else if (c >= 'A' && c <= 'Z') { out.append((char) (c + 32)); lastDash = false; }
            else if (!lastDash) { out.append('-'); lastDash = true; }
        }
        int a = 0, b = out.length();
        while (a < b && out.charAt(a) == '-') a++;
        while (b > a && out.charAt(b - 1) == '-') b--;
        return out.substring(a, b);
    }

    public record Picked(String name, Path dir, String branch) { }

    /** 第一次用原名，之后加 4 位十六进制随机后缀；26 次都撞上回 null。 */
    public static Picked pickUnique(Path root, String desired, java.util.function.Supplier<String> suffix,
                                    Predicate<Path> dirTaken, Predicate<String> branchTaken) {
        for (int n = 0; n < NAME_ATTEMPTS; n++) {
            String name = n == 0 ? desired : desired + "-" + suffix.get();
            String branch = branchName(name);
            Path dir = nameToDir(root, name);
            if (!dirTaken.test(dir) && !branchTaken.test(branch)) return new Picked(name, dir, branch);
        }
        return null;
    }

    private static final SecureRandom RNG = new SecureRandom();

    public static Picked generateUnique(Path root, String desired) {
        return pickUnique(root, desired,
                () -> String.format("%04x", RNG.nextInt(0x10000)),
                d -> Files.exists(d, LinkOption.NOFOLLOW_LINKS),
                b -> branchExists(root, b));
    }

    static boolean branchExists(Path root, String branch) {
        try {
            return Git.run(root, "show-ref", "--verify", "--quiet", "refs/heads/" + branch).ok();
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean gitRepo(Path dir) {
        return dir != null && Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
    }

    // ── porcelain ──────────────────────────────────────────────────────

    /**
     * {@code git worktree list --porcelain} → 条目表，第一条是主工作树。
     * 只有 {@code worktree} 行、没有别的行的段落不算（不完整）；最后一段没有以换行收尾也不算（被截断）。
     */
    public static List<Entry> parse(String text) {
        boolean terminated = !text.isEmpty() && text.charAt(text.length() - 1) == '\n';
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\n", -1)) lines.add(l.strip());
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        List<String[]> pairs = new ArrayList<>();
        List<String> stanza = new ArrayList<>();
        for (String l : lines) {
            if (l.isEmpty()) {
                String[] p = stanza(stanza);
                if (p != null) pairs.add(p);
                stanza = new ArrayList<>();
            } else stanza.add(l);
        }
        if (terminated && !stanza.isEmpty()) {
            String[] p = stanza(stanza);
            if (p != null) pairs.add(p);
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            out.add(new Entry(i == 0 ? Kind.MAIN : Kind.LINKED, pairs.get(i)[0], pairs.get(i)[1]));
        }
        return out;
    }

    private static String[] stanza(List<String> ls) {
        String d = null, b = null;
        for (String h : ls) {
            if (h.startsWith("worktree ")) d = h.substring(9);
            else if (h.startsWith("branch ")) b = parseBranch(h.substring(7));
        }
        return d != null && ls.size() > 1 ? new String[]{d, b} : null;
    }

    private static String parseBranch(String raw) {
        if (raw.equals("(detached HEAD)")) return null;
        if (raw.startsWith("refs/heads/")) return raw.substring(11);
        return raw;
    }

    // ── 操作 ───────────────────────────────────────────────────────────

    public static List<Entry> list(Path dir) throws GitException {
        Git.Out o = run(dir, "git worktree list", "worktree", "list", "--porcelain");
        return parse(o.stdout());
    }

    public static void add(Path root, String branch, Path dir) throws GitException {
        run(root, "git worktree add", "worktree", "add", "-b", branch, dir.toString());
    }

    /**
     * {@code worktree remove --force}，目录还在就自己删（不跟软链），然后删分支（只删 {@code agentforge/} 前缀的）。
     */
    public static void remove(Path root, String dir, String branch) throws GitException {
        try { Git.run(Path.of(dir), "fsmonitor--daemon", "stop"); } catch (IOException | RuntimeException ignored) { }
        run(root, "git worktree remove", "worktree", "remove", "--force", dir);
        Path d = Path.of(dir);
        if (Files.exists(d, LinkOption.NOFOLLOW_LINKS)) {
            for (int i = 0; i < 5 && Files.exists(d, LinkOption.NOFOLLOW_LINKS); i++) {
                try { rmTreeNoFollow(d); } catch (IOException ignored) { }
            }
            if (Files.exists(d, LinkOption.NOFOLLOW_LINKS)) throw new GitException("目录还删不掉，看一眼文件系统是否拒绝");
        }
        branchDelete(root, branch);
    }

    public static void branchDelete(Path root, String branch) throws GitException {
        if (!ownBranch(branch)) return;
        run(root, "git branch -D", "branch", "-D", branch);
    }

    /** 删一棵树，不跟软链。 */
    public static void rmTreeNoFollow(Path p) throws IOException { io.ailink.agentforge.agent.util.Trees.rmNoFollow(p); }

    /**
     * 重置到 base：base 带 {@code /} 先 fetch（失败但本地有这个 rev 就算了）→ {@code reset --hard}
     * → {@code clean -ffdx} → 子模块同样处理 → 看 status。干净回 null，否则回 status 文本。
     */
    public static String reset(Path dir, String base) throws GitException {
        int sep = base.indexOf('/');
        if (sep >= 0) {
            Git.Out f = runRaw(dir, "fetch", base.substring(0, sep), base.substring(sep + 1));
            if (!f.ok() && !revExists(dir, base)) throw new GitException(f.why("git fetch"));
        }
        run(dir, "git reset --hard", "reset", "--hard", base);
        run(dir, "git clean -ffdx", "clean", "-ffdx");
        run(dir, "git submodule", "submodule", "update", "--init", "--recursive", "--force");
        run(dir, "git submodule", "submodule", "foreach", "--recursive", "git", "reset", "--hard");
        run(dir, "git submodule", "submodule", "foreach", "--recursive", "git", "clean", "-fdx");
        Git.Out st = run(dir, "git status", "status", "--porcelain=v1", "-z");
        String s = st.stdout();
        return s.isBlank() ? null : s;
    }

    private static boolean revExists(Path dir, String base) {
        try { return Git.run(dir, "rev-parse", "--verify", "--quiet", base).ok(); } catch (IOException e) { return false; }
    }

    /** {@code origin/HEAD} 指向的分支（如 {@code origin/main}）；探测不到回 null —— 不猜 main。 */
    public static String defaultBranch(Path root) {
        try {
            Git.Out o = Git.run(root, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
            String s = o.stdout().strip();
            return o.ok() && !s.isEmpty() ? s : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static Git.Out runRaw(Path dir, String... args) throws GitException {
        try {
            return Git.run(dir, args);
        } catch (IOException e) {
            throw new GitException("git 跑不起来：" + e.getMessage());
        }
    }

    private static Git.Out run(Path dir, String op, String... args) throws GitException {
        Git.Out o = runRaw(dir, args);
        if (!o.ok()) throw new GitException(o.why(op));
        return o;
    }
}
