package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.session.GalleryImage;
import io.ailink.agentforge.agent.transcript.Entry;
import io.ailink.agentforge.agent.transcript.SubRun;
import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Window;
import io.ailink.agentforge.tui.text.Width;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** markdown 渲染：各构件、流式半截、没收口的代码块、中文表格、软折行悬挂缩进、宽度变化。 */
class MarkdownTest {

    /** 排出来的文字（去掉左边距那两格）。 */
    private static String md(String src, int width) {
        Chunk c = new Chunk();
        Markdown.render(c, src, width, "", null);
        return c.text();
    }

    private static Chunk chunk(String src, int width) {
        Chunk c = new Chunk();
        Markdown.render(c, src, width, "", null);
        return c;
    }

    private static boolean hasFace(Chunk c, String text, String face) {
        int i = c.text().indexOf(text);
        assertTrue(i >= 0, "找不到「" + text + "」：\n" + c.text());
        for (Chunk.Span s : c.spans) if (s.a() <= i && i + text.length() <= s.b() && face.equals(s.face())) return true;
        return false;
    }

    @Test
    void headingsAreDistinctAndMarkersDropped() {
        Chunk c = chunk("# 一级\n\n## 二级\n\n### 三级\n\n#### 四级", 60);
        String t = c.text();
        assertFalse(t.contains("#"), t);
        assertTrue(hasFace(c, "一级", "markdown-header-face-1"));
        assertTrue(hasFace(c, "二级", "markdown-header-face-2"));
        assertTrue(hasFace(c, "三级", "markdown-header-face-3"));
        assertTrue(hasFace(c, "四级", "markdown-header-face-4"));
        assertTrue(t.contains("一级\n━━━━\n"), "一级标题下面一道和字一样宽的粗线：\n" + t);
    }

    @Test
    void inlineStyles() {
        Chunk c = chunk("a **粗** *斜* ~~删~~ `code` [链接](https://x.dev) <https://y.dev> <b>标签</b>", 80);
        String t = c.text();
        assertEquals("a 粗 斜 删 code 链接 (https://x.dev) https://y.dev 标签\n", t);
        assertTrue(hasFace(c, "粗", "markdown-bold"));
        assertTrue(hasFace(c, "斜", "markdown-italic"));
        assertTrue(hasFace(c, "删", "markdown-strike"));
        assertTrue(hasFace(c, "code", "markdown-inline-code"));
        assertTrue(hasFace(c, "链接", "markdown-link"));
        assertTrue(hasFace(c, "(https://x.dev)", "markdown-link-url"));
        assertTrue(hasFace(c, "https://y.dev", "markdown-link"));
    }

    @Test
    void linksAreClickable() {
        AtomicReference<String> opened = new AtomicReference<>();
        Chunk c = new Chunk();
        Markdown.render(c, "看 [文档](https://docs.example) 和 www.example.com", 80, "", (ed, u) -> opened.set(u));
        assertFalse(c.acts.isEmpty());
        c.acts.getFirst().action().accept(null);
        assertEquals("https://docs.example", opened.get());
        assertTrue(c.acts.size() >= 3, "裸链接（autolink 扩展）也能点");
    }

    @Test
    void listsWithMarkersAndNesting() {
        String t = md("- 一\n- 二\n  - 子\n    - 孙\n\n1. 甲\n2. 乙\n\n- [ ] 待办\n- [x] 完成", 60);
        assertTrue(t.contains("• 一\n• 二\n  ◦ 子\n    ▪ 孙\n"), t);
        assertTrue(t.contains("1. 甲\n2. 乙\n"), t);
        assertTrue(t.contains("☐ 待办\n☑ 完成\n"), t);
        String ten = md("9. a\n10. b", 40);
        assertEquals(" 9. a\n10. b\n", ten, "序号右对齐");
    }

    @Test
    void blockquoteAndRule() {
        Chunk c = chunk("> 引用一\n>\n> 引用 **二**\n\n---\n\n后", 30);
        String t = c.text();
        assertTrue(t.startsWith("│ 引用一\n│\n│ 引用 二\n"), t);
        assertTrue(t.contains("─".repeat(30)), "分隔线铺满宽度：\n" + t);
        assertTrue(hasFace(c, "二", "markdown-bold"));
        assertTrue(hasFace(c, "二", "markdown-blockquote-face"));
    }

    @Test
    void fencedCodeBlockIsShadedLabelledAndHighlighted() {
        Chunk c = chunk("```java\npublic class A { // hi\n  int x = 42;\n}\n```", 60);
        String t = c.text();
        assertTrue(t.startsWith(" java\n  public class A { // hi\n    int x = 42;\n  }\n"), t);
        assertFalse(t.contains("```"));
        assertTrue(hasFace(c, "public", "font-lock-keyword-face"));
        assertTrue(hasFace(c, "// hi", "font-lock-comment-face"));
        assertTrue(hasFace(c, "42", "font-lock-number-face"));
        boolean extended = c.spans.stream().anyMatch(s -> s.extend() && "markdown-code-block".equals(s.face()));
        assertTrue(extended, "代码块衬底铺到行尾");
        Chunk py = chunk("```python\ndef f(): return \"s\"\n```", 60);
        assertTrue(hasFace(py, "def", "font-lock-keyword-face"));
        assertTrue(hasFace(py, "\"s\"", "font-lock-string-face"));
        Chunk unknown = chunk("```nosuchlang\nfoo bar\n```", 60);
        assertTrue(unknown.text().contains("  foo bar\n"), "认不出的语言照原样画");
    }

    @Test
    void unclosedFenceWhileStreamingIsCode() {
        Chunk c = chunk("前言\n\n```bash\necho hi\nls -la", 60);
        String t = c.text();
        assertTrue(t.contains(" bash\n  echo hi\n  ls -la\n"), t);
        assertFalse(t.contains("```"));
        Chunk justOpened = chunk("前言\n\n```", 60);
        assertFalse(justOpened.text().contains("```"), "刚开的围栏不漏记号：\n" + justOpened.text());
    }

    @Test
    void streamingPrefixesRenderWithoutCrashing() {
        String full = "# 标题\n\n段落 **粗体** 和 `代码`\n\n- a\n- b\n\n| 列 | 值 |\n|---|---:|\n| 中文 | 1 |\n\n```py\nx = 1\n```\n> 引\n";
        for (int i = 0; i <= full.length(); i++) {
            String part = full.substring(0, i);
            Chunk c = chunk(part, 40);
            for (Chunk.Span s : c.spans) assertTrue(s.a() >= 0 && s.b() <= c.text().length(), "span 越界 @" + i);
        }
    }

    @Test
    void tableAlignedWithCjkWidths() {
        String t = md("| 名称 | 数 |\n|:--|--:|\n| 中文名 | 1 |\n| ab | 123 |", 60);
        List<String> ls = t.lines().toList();
        assertEquals(6, ls.size(), t);
        int w = Width.stringWidth(ls.getFirst());
        for (String l : ls) assertEquals(w, Width.stringWidth(l), "每行显示宽度一样：\n" + t);
        assertEquals("┌────────┬─────┐", ls.get(0));
        assertEquals("│ 名称   │  数 │", ls.get(1));
        assertEquals("├────────┼─────┤", ls.get(2));
        assertEquals("│ 中文名 │   1 │", ls.get(3));
        assertEquals("│ ab     │ 123 │", ls.get(4));
        assertEquals("└────────┴─────┘", ls.get(5));
    }

    @Test
    void wideTableWrapsCellsThenFallsBackToRecords() {
        String src = "| 名 | 说明 |\n|---|---|\n| a | " + "很长的说明文字".repeat(6) + " |";
        String t = md(src, 40);
        for (String l : t.lines().toList()) assertTrue(Width.stringWidth(l) <= 40, "不超宽：\n" + t);
        assertTrue(t.lines().count() > 5, "长格折成多行：\n" + t);
        String many = "| a | b | c | d | e | f | g | h |\n|---|---|---|---|---|---|---|---|\n| " + "长内容甲乙丙 | ".repeat(8);
        String r = md(many, 24);
        assertTrue(r.contains("a: "), "太窄退成逐行「表头: 值」：\n" + r);
        assertFalse(r.contains("┌"));
    }

    // ── 经过真窗口：软折行的悬挂缩进、宽度变化 ─────────────────────────────

    private static final class View implements AutoCloseable {
        final Editor ed;
        final Buffer buf;
        final TranscriptView tv;
        final AtomicInteger width = new AtomicInteger();

        View(int cols, int rows) {
            ed = new Editor(cols, rows);
            buf = ed.getOrCreateBuffer("*md*");
            buf.put("word-wrap", true);
            ed.showBuffer(ed.selectedWindow(), buf);
            Window w = ed.selectedWindow();
            tv = new TranscriptView(buf, new Blocks.Ctx() {
                @Override public String fullResult(String id) { return null; }
                @Override public SubRun subRun(String id) { return null; }
                @Override public List<GalleryImage> images(String owner) { return List.of(); }
                @Override public void enterChild(Editor e, String runId) { }
                @Override public void openImage(Editor e, GalleryImage g) { }
                @Override public int width() { return ed.textSize(w)[0]; }
            });
        }

        List<String> rows() {
            List<String> out = new ArrayList<>();
            for (String l : ed.render().dump().split("\n")) out.add(l.stripTrailing());
            return out;
        }

        @Override public void close() { }
    }

    @Test
    void softWrapKeepsHangingIndent() {
        View v = new View(30, 12);
        v.tv.update(List.of(Entry.assistant("- 第一项的文字很长很长很长很长很长很长很长很长\n  - 嵌套 aaaa bbbb cccc dddd eeee ffff gggg")));
        List<String> rs = v.rows();
        int i = 0;
        while (!rs.get(i).contains("• 第一项")) i++;
        assertTrue(rs.get(i).startsWith("• • 第一项"), "回答第一行垫 Codex 的 • ，列表记号跟在后面：" + rs);
        assertTrue(rs.get(i + 1).startsWith("    长") && !rs.get(i + 1).startsWith("     "), "续行和文字对齐（悬挂缩进）：" + rs);
        int j = i;
        while (!rs.get(j).contains("◦ 嵌套")) j++;
        assertTrue(rs.get(j + 1).startsWith("      "), "嵌套项的续行缩得更深：" + rs);
        assertFalse(rs.get(j + 1).startsWith("       "), rs.toString());
    }

    @Test
    void blockquoteWrapRepeatsBar() {
        View v = new View(24, 10);
        v.tv.update(List.of(Entry.assistant("> 这是一段很长的引用文字，会被折成好几行显示出来")));
        List<String> rs = v.rows();
        long bars = rs.stream().filter(r -> r.startsWith("  │ ")).count();
        assertTrue(bars >= 2, "引用的续行也有竖条：" + rs);
    }

    @Test
    void widthChangeRerendersTables() {
        View v = new View(60, 12);
        String src = "| 名 | 说明 |\n|---|---|\n| a | " + "说明文字".repeat(8) + " |";
        v.tv.update(List.of(Entry.assistant(src)));
        String wide = v.buf.contents();
        long renders = v.tv.renders();
        v.tv.update(List.of(Entry.assistant(src)));
        assertEquals(renders, v.tv.renders(), "宽度没变、条目没变 ⇒ 不动");
        v.ed.resize(36, 12);
        v.tv.update(List.of(Entry.assistant(src)));
        assertNotEquals(wide, v.buf.contents(), "变窄后表格重排");
        for (String l : v.buf.contents().lines().toList()) assertTrue(Width.stringWidth(l) <= 36, v.buf.contents());
    }

    @Test
    void streamingAppendDoesNotMoveEarlierText() {
        View v = new View(40, 8);
        String a = "第一段。\n\n第二段正在写";
        v.tv.update(List.of(Entry.user("问"), Entry.assistant(a)));
        String before = v.buf.contents();
        v.tv.update(List.of(Entry.user("问"), Entry.assistant(a + "，接着写 **粗")));
        v.tv.update(List.of(Entry.user("问"), Entry.assistant(a + "，接着写 **粗体**。")));
        String after = v.buf.contents();
        int keep = before.indexOf("第二段");
        assertEquals(before.substring(0, keep), after.substring(0, keep), "前面的字不动（往上翻的人不被拽）");
    }

    @Test
    void copyGivesRawMarkdown() {
        View v = new View(60, 12);
        String src = "## 标题\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```js\nlet x = 1\n```";
        v.tv.update(List.of(Entry.assistant(src)));
        int p = v.buf.contents().indexOf("标题");
        assertEquals(src, v.tv.rawAt(p));
    }
}
