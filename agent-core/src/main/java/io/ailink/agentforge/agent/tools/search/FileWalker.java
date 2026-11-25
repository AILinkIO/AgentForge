package io.ailink.agentforge.agent.tools.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 走目录树拿候选文件，不 spawn 进程（coxswain 问 {@code git ls-files}，这里自己读 .gitignore）。
 *
 * <p>为什么默认只看"git 会看的文件"：一个仓库根目录上 99% 的文件在 build/、deps/、.git/ 里，
 * 而那是用户自己都不看的东西。{@link #candidates} 近似 {@code git ls-files --cached --others
 * --exclude-standard}：读全局 {@code ~/.config/git/ignore}、{@code .git/info/exclude}、各层
 * {@code .gitignore}（否定、只认目录、锚定、{@code **}），被忽略的目录整棵剪掉（与 git 同：
 * 父目录被忽略时子项无法再被 {@code !} 捞回）。跳过嵌套仓库（子模块）。
 *
 * <p>⚠️ 递归**不跟软链**：root 里一条指向外面的软链不会把搜索漏出去。
 */
public final class FileWalker {
    private FileWalker() { }

    /** 走到这么多条就停：防一棵病态的树吃光内存。 */
    public static final int MAX_ENTRIES = 500_000;

    public enum Kind { FILE, DIR, ANY }

    /** dir 或其祖先里第一个带 {@code .git} 的目录；null = 不在 git 仓里。 */
    public static Path repoTop(Path dir) {
        for (Path p = dir.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return p;
        }
        return null;
    }

    /**
     * git 会看的文件（相对 root，{@code /} 分隔，排好序）。null = 不在 git 仓里（调用方走"全搜"老路）。
     *
     * @param startRel 从 root 下哪里开始（"" = 整棵）；可以是一个文件
     */
    public static List<String> candidates(Path root, String startRel) {
        Path absRoot = root.toAbsolutePath().normalize();
        Path top = repoTop(absRoot);
        if (top == null) return null;
        List<Frame> frames = new ArrayList<>();
        Path global = globalIgnore();
        if (global != null) addFrame(frames, "", global);
        Path dotGit = top.resolve(".git");
        if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) addFrame(frames, "", dotGit.resolve("info").resolve("exclude"));
        addFrame(frames, "", top.resolve(".gitignore"));

        Path start = startRel.isEmpty() ? absRoot : absRoot.resolve(startRel).normalize();
        // 从仓库顶一路下到 start：每一层先判它自己被不被忽略，再装它的 .gitignore
        String startToTop = rel(top, start);
        Path cur = top;
        String curRel = "";
        if (!startToTop.isEmpty()) {
            String[] parts = startToTop.split("/");
            for (int i = 0; i < parts.length; i++) {
                cur = cur.resolve(parts[i]);
                curRel = curRel.isEmpty() ? parts[i] : curRel + "/" + parts[i];
                boolean last = i == parts.length - 1;
                boolean isDir = Files.isDirectory(cur, LinkOption.NOFOLLOW_LINKS);
                if (ignored(frames, curRel, isDir)) return List.of();
                if (!last || isDir) addFrame(frames, curRel, cur.resolve(".gitignore"));
            }
        }
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(start, LinkOption.NOFOLLOW_LINKS)) out.add(rel(absRoot, start));
            return out;
        }
        walkGit(absRoot, top, start, curRel, frames, out);
        Collections.sort(out);
        return out;
    }

    private static void walkGit(Path root, Path top, Path dir, String dirRel, List<Frame> frames, List<String> out) {
        for (Entry e : list(dir)) {
            if (out.size() >= MAX_ENTRIES) return;
            if (e.name.equals(".git")) continue;
            String r = dirRel.isEmpty() ? e.name : dirRel + "/" + e.name;
            if (ignored(frames, r, e.dir)) continue;
            if (e.dir) {
                if (Files.exists(e.path.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) continue; // 子模块 / 嵌套仓库
                int mark = frames.size();
                addFrame(frames, r, e.path.resolve(".gitignore"));
                walkGit(root, top, e.path, r, frames, out);
                while (frames.size() > mark) frames.removeLast();
            } else {
                out.add(rel(root, e.path));
            }
        }
    }

    /** 不看忽略规则，整棵走（all=true / 不是 git 仓）。只跳 {@code .git}。结果相对 root、排好序。 */
    public static List<String> walkAll(Path root, String startRel, Kind kind) {
        Path absRoot = root.toAbsolutePath().normalize();
        Path start = startRel.isEmpty() ? absRoot : absRoot.resolve(startRel).normalize();
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(start, LinkOption.NOFOLLOW_LINKS) && kind != Kind.DIR) out.add(rel(absRoot, start));
            return out;
        }
        walkPlain(absRoot, start, kind, out);
        Collections.sort(out);
        return out;
    }

    private static void walkPlain(Path root, Path dir, Kind kind, List<String> out) {
        for (Entry e : list(dir)) {
            if (out.size() >= MAX_ENTRIES) return;
            if (e.name.equals(".git")) continue;
            if (e.dir) {
                if (kind != Kind.FILE) out.add(rel(root, e.path));
                walkPlain(root, e.path, kind, out);
            } else if (kind != Kind.DIR) {
                out.add(rel(root, e.path));
            }
        }
    }

    static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private record Entry(String name, Path path, boolean dir) { }

    private static List<Entry> list(Path dir) {
        List<Entry> es = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                boolean d;
                try {
                    d = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory();
                } catch (IOException ex) { continue; }
                es.add(new Entry(p.getFileName().toString(), p, d));
            }
        } catch (IOException | SecurityException ignored) { }
        es.sort((a, b) -> a.name.compareTo(b.name));
        return es;
    }

    private static Path globalIgnore() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        Path p = base.resolve("git").resolve("ignore");
        return Files.isRegularFile(p) ? p : null;
    }

    // ── .gitignore ─────────────────────────────────────────────────────

    record Rule(Pattern re, boolean negate, boolean dirOnly) { }

    record Frame(String base, List<Rule> rules) { }

    private static void addFrame(List<Frame> frames, String base, Path file) {
        if (!Files.isRegularFile(file)) return;
        try {
            List<Rule> rs = parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!rs.isEmpty()) frames.add(new Frame(base, rs));
        } catch (IOException | RuntimeException ignored) { }
    }

    static boolean ignored(List<Frame> frames, String relToTop, boolean isDir) {
        boolean ig = false;
        for (Frame f : frames) {
            String sub;
            if (f.base.isEmpty()) sub = relToTop;
            else if (relToTop.startsWith(f.base + "/")) sub = relToTop.substring(f.base.length() + 1);
            else continue;
            for (Rule r : f.rules) {
                if (r.dirOnly && !isDir) continue;
                if (r.re.matcher(sub).matches()) ig = !r.negate;
            }
        }
        return ig;
    }

    /** 一份 .gitignore 的文本 → 规则表。 */
    static List<Rule> parse(String text) {
        List<Rule> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            // 行尾空白去掉，除非被反斜杠转义
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ' && !(end >= 2 && line.charAt(end - 2) == '\\')) end--;
            line = line.substring(0, end);
            if (line.isEmpty() || line.startsWith("#")) continue;
            boolean neg = false;
            if (line.startsWith("!")) { neg = true; line = line.substring(1); }
            else if (line.startsWith("\\!") || line.startsWith("\\#")) line = line.substring(1);
            boolean dirOnly = false;
            if (line.endsWith("/")) { dirOnly = true; line = line.substring(0, line.length() - 1); }
            if (line.isEmpty()) continue;
            boolean anchored = line.indexOf('/') >= 0;
            if (line.startsWith("/")) line = line.substring(1);
            try {
                String body = convert(line);
                out.add(new Rule(Pattern.compile(anchored ? body : "(?:.*/)?" + body, Pattern.DOTALL), neg, dirOnly));
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    private static String convert(String g) {
        StringBuilder r = new StringBuilder();
        int n = g.length();
        for (int i = 0; i < n; i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < n && g.charAt(i + 1) == '*') {
                boolean atStart = i == 0 || g.charAt(i - 1) == '/';
                boolean slashAfter = i + 2 < n && g.charAt(i + 2) == '/';
                boolean atEnd = i + 2 == n;
                if (atStart && slashAfter) { r.append("(?:.*/)?"); i += 2; }
                else if (atStart && atEnd) { r.append(".*"); i += 1; }
                else { r.append(".*"); i += 1; }
            } else if (c == '*') r.append("[^/]*");
            else if (c == '?') r.append("[^/]");
            else if (c == '\\' && i + 1 < n) r.append(Pattern.quote(String.valueOf(g.charAt(++i))));
            else if (c == '[') {
                int j = g.indexOf(']', i + 2);
                if (j < 0) { r.append("\\["); continue; }
                String cls = g.substring(i + 1, j);
                if (cls.startsWith("!")) cls = "^" + cls.substring(1);
                r.append('[').append(cls.replace("\\", "\\\\").replace("[", "\\[")).append(']');
                i = j;
            } else r.append(Pattern.quote(String.valueOf(c)));
        }
        return r.toString();
    }
}
