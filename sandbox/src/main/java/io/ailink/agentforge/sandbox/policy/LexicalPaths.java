package io.ailink.agentforge.sandbox.policy;

import io.ailink.agentforge.sandbox.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 词法路径运算（不碰文件系统、不解符号链接）：策略判定都在这一层做（同 Codex {@code PathUri} 的 starts_with / lexical_depth）。
 * 语义照 .NET 的 {@code System.IO.Path}（C# 版用它）；Windows 上不分大小写、两种分隔符都认。
 */
public final class LexicalPaths {
    private LexicalPaths() {
    }

    /** 本机是不是 Windows（决定分隔符与大小写规则）。 */
    public static final boolean WINDOWS = Platform.isWindows();

    /** 路径比较器（Windows 不分大小写）。 */
    public static final Comparator<String> COMPARATOR = WINDOWS ? String.CASE_INSENSITIVE_ORDER : Comparator.naturalOrder();

    public static boolean isSeparator(char c) {
        return c == '/' || (WINDOWS && c == '\\');
    }

    /** {@code Path.IsPathFullyQualified}（Windows：{@code C:\x}、{@code \\server\share}；{@code \x} 与 {@code C:x} 不算）。 */
    public static boolean isAbsolute(String path) {
        if (path == null || path.isEmpty()) return false;
        if (WINDOWS) {
            return (path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':' && isSeparator(path.charAt(2)))
                    || (path.length() >= 2 && isSeparator(path.charAt(0)) && isSeparator(path.charAt(1)));
        }
        return path.charAt(0) == '/';
    }

    /** {@code Path.IsPathRooted}。 */
    public static boolean isRooted(String path) {
        if (path == null || path.isEmpty()) return false;
        if (isSeparator(path.charAt(0))) return true;
        return WINDOWS && path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    /** {@code Path.GetFullPath}：相对的按进程 cwd，折叠 . / .. 与重复分隔符（⛔ 解链接）。 */
    public static String fullPath(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private static int nextSeparator(String p, int from) {
        for (int i = from; i < p.length(); i++) if (isSeparator(p.charAt(i))) return i;
        return -1;
    }

    /** {@code Path.GetPathRoot}：Unix {@code /} 或空；Windows {@code C:\}、{@code \\server\share\}、{@code \}。 */
    public static String rootOf(String path) {
        if (path == null || path.isEmpty()) return "";
        if (!WINDOWS) return path.charAt(0) == '/' ? "/" : "";
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':')
            return path.length() >= 3 && isSeparator(path.charAt(2)) ? path.substring(0, 3) : path.substring(0, 2);
        if (path.length() >= 2 && isSeparator(path.charAt(0)) && isSeparator(path.charAt(1))) {
            int s = nextSeparator(path, 2);
            s = s < 0 ? -1 : nextSeparator(path, s + 1);
            return s < 0 ? path : path.substring(0, s + 1);
        }
        if (isSeparator(path.charAt(0))) return path.substring(0, 1);
        return "";
    }

    /** 规范化：折叠 . / ..，去掉尾部分隔符（根除外）。 */
    public static String normalize(String path) {
        String full = fullPath(path);
        String root = rootOf(full);
        int end = full.length();
        while (end > root.length() && isSeparator(full.charAt(end - 1))) end--;
        return full.substring(0, end);
    }

    /** {@code Path.Join}：两段之间补一个分隔符（已有就不补），不做规范化。 */
    public static String join(String root, String name) {
        if (root.isEmpty()) return name;
        if (name.isEmpty()) return root;
        if (isSeparator(root.charAt(root.length() - 1)) || isSeparator(name.charAt(0))) return root + name;
        return root + (WINDOWS ? '\\' : '/') + name;
    }

    /** {@code Path.Combine}：第二段有根 ⇒ 就是它。 */
    public static String combine(String a, String b) {
        return isRooted(b) || a.isEmpty() ? b : join(a, b);
    }

    /** 相对路径按 {@code baseDir} 解析后规范化。 */
    public static String resolve(String path, String baseDir) {
        return normalize(isAbsolute(path) ? path : combine(baseDir, path));
    }

    private static boolean startsWithPath(String s, String prefix) {
        return WINDOWS ? s.regionMatches(true, 0, prefix, 0, prefix.length()) : s.startsWith(prefix);
    }

    /** {@code path} 就是 {@code root} 或在它下面（按分量比，不按字符前缀）。两者都须已规范化。 */
    public static boolean isUnder(String path, String root) {
        return startsWithPath(path, root)
                && (path.length() == root.length()
                || (!root.isEmpty() && isSeparator(root.charAt(root.length() - 1)))
                || isSeparator(path.charAt(root.length())));
    }

    public static boolean same(String a, String b) {
        return WINDOWS ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    /** 根以下的分量数（根 = 0）。 */
    public static int depth(String path) {
        String root = rootOf(path);
        int d = 0;
        boolean inSeg = false;
        for (int i = root.length(); i < path.length(); i++) {
            if (isSeparator(path.charAt(i))) inSeg = false;
            else {
                if (!inSeg) d++;
                inSeg = true;
            }
        }
        return d;
    }

    /** cwd 所在文件系统的根（Unix 上是 {@code /}）。 */
    public static String fileSystemRoot(String cwd) {
        String r = rootOf(cwd);
        return r.isEmpty() ? cwd : r;
    }

    public static boolean isFileSystemRoot(String path) {
        String r = rootOf(path);
        return !r.isEmpty() && same(r, path);
    }

    /** {@code Path.GetDirectoryName}：根 / 空 ⇒ {@code null}；没有分隔符 ⇒ 空串。 */
    public static String directoryName(String path) {
        if (path == null || path.isEmpty()) return null;
        String root = rootOf(path);
        if (path.length() == root.length()) return null;
        int i = path.length() - 1;
        while (i >= root.length() && !isSeparator(path.charAt(i))) i--;
        if (i < root.length()) return root;
        int end = i;
        while (end > root.length() && isSeparator(path.charAt(end - 1))) end--;
        return path.substring(0, Math.max(end, root.length()));
    }

    /** {@code Path.GetFileName}。 */
    public static String fileName(String path) {
        int i = path.length() - 1;
        while (i >= 0 && !isSeparator(path.charAt(i)) && !(WINDOWS && path.charAt(i) == ':')) i--;
        return path.substring(i + 1);
    }

    /** {@code root} 之下的 {@code path} 相对它的部分（调用方保证 {@link #isUnder} 且不同）。 */
    public static String relative(String root, String path) {
        int s = root.length();
        while (s < path.length() && isSeparator(path.charAt(s))) s++;
        return path.substring(s);
    }

    /** 项目根下的子路径：⛔ 有根的、⛔ 逃出根（..）⇒ {@code null}（同 Codex resolve_scoped_workspace_path）。 */
    public static String joinScoped(String root, String subpath) {
        if (subpath.isEmpty()) return root;
        if (isRooted(subpath)) return null;
        String j = normalize(combine(root, subpath));
        return isUnder(j, root) ? j : null;
    }

    /** 不分大小写（Windows）的去重键。 */
    public static String key(String path) {
        return WINDOWS ? path.toUpperCase(Locale.ROOT) : path;
    }

    /** 去重保序（路径比较规则）。 */
    public static List<String> distinct(Iterable<String> paths) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String p : paths) if (seen.add(key(p))) out.add(p);
        return out;
    }

    /** 列表里有和 {@code x} 相同的路径。 */
    public static boolean containsPath(Iterable<String> xs, String x) {
        for (String s : xs) if (same(s, x)) return true;
        return false;
    }
}
