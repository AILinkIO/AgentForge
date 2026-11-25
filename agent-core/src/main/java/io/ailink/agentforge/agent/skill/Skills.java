package io.ailink.agentforge.agent.skill;

import io.ailink.agentforge.agent.config.AgentPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * skill：人写好的操作手册（coxswain {@code (coxn agent skill)}，对齐 opencode / Claude Code 的 skills）。
 * 一个 skill = 一个目录 + {@code SKILL.md}（YAML frontmatter 写 name / description，正文是步骤）。
 *
 * <p>两层：系统提示里只列**目录**（{@link #promptSection}），模型对上了再调 {@code skill} 工具装整份。
 *
 * <p>从哪儿找（顺序即优先级，同名先到的赢 —— 项目盖用户）：
 * <pre>
 *   $AGENTFORGE_SKILLS
 *   &lt;root&gt;/.agentforge/skills  &lt;root&gt;/.claude/skills  &lt;root&gt;/.agents/skills
 *   &lt;配置目录&gt;/skills  ~/.claude/skills  ~/.agents/skills
 * </pre>
 * 找不到的目录、读不动 / 二进制的文件、名字不合法的一律跳过 —— 发现是尽力而为。
 */
public final class Skills {
    private Skills() { }

    public static final int MAX_FILES = 10;
    public static final int MAX_CHARS = 64000;

    public record Skill(String name, String description, Path path, Path dir) { }

    public static List<Path> dirs(Path root) {
        List<Path> out = new ArrayList<>();
        String extra = System.getenv("AGENTFORGE_SKILLS");
        if (extra != null && !extra.isBlank()) out.add(Path.of(extra));
        if (root != null && !root.toString().isEmpty()) {
            out.add(root.resolve(".agentforge").resolve("skills"));
            out.add(root.resolve(".claude").resolve("skills"));
            out.add(root.resolve(".agents").resolve("skills"));
        }
        out.add(AgentPaths.configDir().resolve("skills"));
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            out.add(Path.of(home, ".claude", "skills"));
            out.add(Path.of(home, ".agents", "skills"));
        }
        return out;
    }

    public static List<Skill> scan(List<Path> dirs) {
        List<Skill> acc = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) continue;
            List<Path> subs = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                for (Path p : ds) if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) subs.add(p);
            } catch (IOException | SecurityException e) {
                continue;
            }
            subs.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            for (Path sub : subs) {
                Path md = sub.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) continue;
                Skill sk = read(sub.getFileName().toString(), md, sub);
                if (sk != null && seen.add(sk.name())) acc.add(sk);
            }
        }
        return acc;
    }

    public static Skill find(List<Skill> skills, String name) {
        for (Skill s : skills) if (s.name().equals(name)) return s;
        return null;
    }

    private static Skill read(String dirname, Path md, Path dir) {
        try {
            byte[] bytes = Files.readAllBytes(md);
            if (binary(bytes)) return null;
            Frontmatter fm = parseFrontmatter(new String(bytes, StandardCharsets.UTF_8));
            String name = nonEmpty(fm.fields().get("name"), dirname);
            String desc = nonEmpty(fm.fields().get("description"), "（没有描述）");
            return validName(name) ? new Skill(name, desc, md, dir) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static boolean binary(byte[] b) {
        int n = Math.min(b.length, 8000);
        for (int i = 0; i < n; i++) if (b[i] == 0) return true;
        return false;
    }

    private static String nonEmpty(String v, String dflt) { return v == null || v.isEmpty() ? dflt : v; }

    /** 字母数字 {@code - _ .}，1–64 个：免得名字带空格的 skill 在提示词里被读成两个词。 */
    static boolean validName(String s) {
        if (s == null || s.isEmpty() || s.length() > 64) return false;
        return s.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.');
    }

    // ── frontmatter ────────────────────────────────────────────────────

    public record Frontmatter(Map<String, String> fields, String body) { }

    /**
     * 只认最常见的写法，**不是 YAML 解析器**：第一行 {@code ---} 到下一个 {@code ---}；{@code key: value}
     * 切第一个冒号；以空白开头的行是上一个值的续行（空格接上）；值位置单独的 {@code > | >- |-} 当空串；
     * 成对引号去掉；认不出的行跳过。没闭合 = 没有 frontmatter。宁可少认，不可认错。
     */
    public static Frontmatter parseFrontmatter(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) return new Frontmatter(Map.of(), text);
        LinkedHashMap<String, String> fm = new LinkedHashMap<>();
        String lastKey = null;
        for (int n = 1; n < lines.length; n++) {
            String l = lines[n];
            if (l.strip().equals("---")) {
                Map<String, String> done = new LinkedHashMap<>();
                fm.forEach((k, v) -> done.put(k, unquote(v)));
                StringBuilder body = new StringBuilder();
                for (int k = n + 1; k < lines.length; k++) {
                    if (k > n + 1) body.append('\n');
                    body.append(lines[k]);
                }
                return new Frontmatter(done, body.toString());
            }
            if (lastKey != null && !l.isEmpty() && (l.charAt(0) == ' ' || l.charAt(0) == '\t')) {
                String more = l.strip();
                String old = fm.get(lastKey);
                fm.put(lastKey, more.isEmpty() ? old : old.isEmpty() ? more : old + " " + more);
                continue;
            }
            int c = l.indexOf(':');
            if (c < 0) continue;
            String k = l.substring(0, c).strip();
            String v = l.substring(c + 1).strip();
            if (k.isEmpty()) continue;
            if (v.equals(">") || v.equals("|") || v.equals(">-") || v.equals("|-")) v = "";
            fm.put(k, v);
            lastKey = k;
        }
        return new Frontmatter(Map.of(), text);
    }

    private static String unquote(String s) {
        int n = s.length();
        if (n >= 2 && ((s.charAt(0) == '"' && s.charAt(n - 1) == '"') || (s.charAt(0) == '\'' && s.charAt(n - 1) == '\''))) {
            return s.substring(1, n - 1);
        }
        return s;
    }

    // ── 提示词里那段 ───────────────────────────────────────────────────

    /** 一行一条（描述只取第一行、截到 200 字）；没有 skill 就是空串（不白烧 token、不教模型找不存在的工具）。 */
    public static String promptSection(List<Skill> skills) {
        if (skills.isEmpty()) return "";
        StringBuilder b = new StringBuilder("\n## 可用的 skill\n")
                .append("下面是用户 / 项目准备好的操作手册。手上的活对上其中一条时，**先用 skill 工具")
                .append("把它装进来再动手**（name 一字不差）；对不上就别装 —— 每份都是几百行。\n");
        for (Skill s : skills) b.append("- ").append(s.name()).append("：").append(oneLine(s.description(), 200)).append('\n');
        return b.toString();
    }

    /** 当前工作区的目录段（宿主拼系统提示用）：现扫现给，不缓存。 */
    public static String promptSection(Path root) { return promptSection(scan(dirs(root))); }

    static String oneLine(String s, int limit) {
        int nl = s.indexOf('\n');
        String t = (nl < 0 ? s : s.substring(0, nl)).strip();
        return t.length() > limit ? t.substring(0, limit) + "…" : t;
    }

    // ── 装载 ───────────────────────────────────────────────────────────

    /**
     * 给模型的那段：正文（去掉 frontmatter）+ 基目录 + 旁边的文件（绝对路径，最多 10 个）。
     * 基目录多半在工作根外面，read-file 到不了，所以写明"用 run-command 的 cat 看"。
     */
    public static String load(Skill sk) throws IOException {
        String text = Files.readString(sk.path(), StandardCharsets.UTF_8);
        String body = parseFrontmatter(text).body().strip();
        if (body.length() > MAX_CHARS) {
            body = body.substring(0, MAX_CHARS) + "\n\n[SKILL.md 共 " + body.length() + " 字符，这里是前 " + MAX_CHARS
                    + " 个；其余用 run-command 看 " + sk.path() + "]";
        }
        List<Path> files = sideFiles(sk.dir());
        StringBuilder b = new StringBuilder("# Skill: ").append(sk.name()).append("\n\n").append(body)
                .append("\n\n---\n基目录：").append(sk.dir())
                .append("\n正文里的相对路径（scripts/、reference/ …）相对于这个目录。");
        if (!files.isEmpty()) {
            b.append("\n旁边的文件（最多列 ").append(MAX_FILES).append(" 个；多半在工作根目录外面，用 run-command 的 cat 看）：\n");
            for (Path f : files) b.append("- ").append(f).append('\n');
        }
        return b.toString();
    }

    /** SKILL.md 之外的文件，深度 ≤ 3，不跟软链进目录。 */
    static List<Path> sideFiles(Path dir) {
        List<Path> acc = new ArrayList<>();
        walk(dir, 0, true, acc);
        return acc;
    }

    private static void walk(Path d, int depth, boolean top, List<Path> acc) {
        if (acc.size() >= MAX_FILES || depth >= 3) return;
        List<Path> es = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            for (Path p : ds) es.add(p);
        } catch (IOException | SecurityException e) {
            return;
        }
        es.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        for (Path p : es) {
            if (acc.size() >= MAX_FILES) return;
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) walk(p, depth + 1, false, acc);
            else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && !(top && p.getFileName().toString().equals("SKILL.md"))) acc.add(p);
        }
    }
}
