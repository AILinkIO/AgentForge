package io.ailink.agentforge.agent.memory;

import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tools.Limit;
import io.ailink.agentforge.agent.util.Fmt;
import io.ailink.agentforge.agent.util.Fnv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * agent 的长期笔记：一个工作区一个目录（coxswain {@code (coxn agent memory)}）。
 *
 * <pre>  &lt;配置目录&gt;/memory/&lt;basename&gt;-&lt;路径指纹&gt;/*.md</pre>
 *
 * 三条规矩：
 * <ol>
 *   <li>按工作区分目录：basename 给人看，8 位 FNV-1a 指纹（规范化绝对路径）防同名目录串。</li>
 *   <li>只放 {@code .md}：读者是模型与人；别的扩展名当场拒绝，不静默改名。</li>
 *   <li>它不是第二个工作根：路径限定的根是这个目录本身，与项目根互不相通。</li>
 * </ol>
 * 写笔记不走审批（这是 agent 自己的笔记本）；代价是被诱导写下的错笔记会被下次会话读回去 ——
 * 防线是"用户随时能读能删"（纯文本、目录名可读）。
 */
public final class Memory {
    private Memory() { }

    public static final String EXT = ".md";
    public static final int MAX_CHARS = 64000;

    /** {@code $AGENTFORGE_MEMORY} 优先，否则 {@code AgentPaths.configDir()/memory}。 */
    public static Path root() {
        String env = System.getenv("AGENTFORGE_MEMORY");
        if (env != null && !env.isBlank()) return Path.of(env);
        return AgentPaths.configDir().resolve("memory");
    }

    /** 工作区路径 → 它的笔记目录（null = 没有项目）。 */
    public static Path dirFor(Path workspaceRoot) {
        String tag = workspaceTag(workspaceRoot);
        return tag == null ? null : root().resolve(tag);
    }

    /** {@code <安全化的 basename>-<FNV 指纹>}；也可当"项目键"用（会话库按项目拆时）。 */
    public static String workspaceTag(Path workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.toString().isEmpty()) return null;
        Path canon = workspaceRoot.toAbsolutePath().normalize();
        String cs = canon.toString().replace('\\', '/');
        Path fn = canon.getFileName();
        String base = fn == null || fn.toString().isEmpty() ? "root" : fn.toString();
        return safe(base) + "-" + Fnv.hex8(cs);
    }

    /** 留字母数字 {@code . - _}，其余换成 {@code -}（要能在 shell 里敲出来）。 */
    static String safe(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(c -> b.appendCodePoint(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '-'));
        return b.toString();
    }

    /** 项目被删除时清掉它的笔记目录；回删掉的目录，没有 / 删失败回 null。 */
    public static Path deleteFor(Path workspaceRoot) {
        Path dir = dirFor(workspaceRoot);
        if (dir == null || !Files.exists(dir)) return null;
        try {
            io.ailink.agentforge.agent.util.Trees.rmNoFollow(dir);
            return dir;
        } catch (IOException e) {
            return null;
        }
    }

    static boolean md(String name) {
        return name.length() > EXT.length() && name.toLowerCase(Locale.ROOT).endsWith(EXT);
    }

    static Path resolve(Path workspaceRoot, String name) {
        Path dir = dirFor(workspaceRoot);
        if (dir == null) throw new ToolException("拿不到工作区，memory 目录无从谈起");
        if (!md(name)) throw new ToolException("memory 里只放 markdown：" + Fmt.q(name) + " 要以 " + EXT + " 结尾");
        return Limit.resolveUnder(dir, name);
    }

    /** 这个工作区的全部笔记（相对名，字母序）。目录还不存在 = 空表。 */
    public static List<String> list(Path workspaceRoot) {
        Path dir = dirFor(workspaceRoot);
        List<String> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (md(n)) out.add(n);
            }
        } catch (IOException ignored) { }
        Collections.sort(out);
        return out;
    }

    /** 读一篇；不存在回 null（模型问"有没有记过 X"是正常的）。 */
    public static String read(Path workspaceRoot, String name) {
        Path p = resolve(workspaceRoot, name);
        if (!Files.isRegularFile(p)) return null;
        try { return Files.readString(p, StandardCharsets.UTF_8); } catch (IOException e) { return null; }
    }

    /** 整篇覆盖写（不要 tag：每一篇都是 agent 自己写的，更新笔记是日常）。回写进去的路径。 */
    public static Path write(Path workspaceRoot, String name, String text) {
        if (text == null) throw new ToolException("内容要是字符串");
        if (text.length() > MAX_CHARS) {
            throw new ToolException("这篇太长了（" + text.length() + " 字符，上限 " + MAX_CHARS + "）。笔记记结论，过程留在对话里");
        }
        Path p = resolve(workspaceRoot, name);
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, text.replace("\r\n", "\n"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("写笔记失败：" + e.getMessage());
        }
        return p;
    }
}
