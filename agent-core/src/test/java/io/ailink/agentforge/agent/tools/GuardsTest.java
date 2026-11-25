package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.file.FileToolset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** limit / tag / seen / check / args / diff。 */
class GuardsTest {

    @TempDir Path tmp;

    @Test
    void truncateNote() {
        assertEquals("abc", Limit.truncateNote("abc", 10, "x"));
        String t = Limit.truncateNote("a".repeat(100), 10, "输出");
        assertTrue(t.startsWith("a".repeat(10) + "\n\n[已截断：输出 共 100 个字符，上面是前 10 个]"));
    }

    @Test
    void truncateSpill() throws Exception {
        Limit.spillDir(tmp.resolve("spill"));
        try {
            String t = Limit.truncateNoteSpill("x".repeat(50), 10, "命令输出");
            assertTrue(t.contains("完整内容已存到 "), t);
            try (var s = Files.list(tmp.resolve("spill"))) {
                Path p = s.findFirst().orElseThrow();
                assertEquals(50, Files.readString(p).length());
            }
        } finally {
            Limit.spillDir(null);
        }
    }

    @Test
    void resolveInRoot() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Workspace ws = new Workspace(root);
        assertEquals(root.resolve("a.txt").toString(), Limit.resolveInRoot(ws, "a.txt"));
        assertEquals(root.resolve("sub/b.txt").toString(), Limit.resolveInRoot(ws, "sub/./x/../b.txt"));
        assertThrows(ToolException.class, () -> Limit.resolveInRoot(ws, "../secret"));
        assertThrows(ToolException.class, () -> Limit.resolveInRoot(ws, "../../../../etc/passwd"));
        assertThrows(ToolException.class, () -> Limit.resolveInRoot(ws, "sub/../../secret"));
        assertThrows(ToolException.class, () -> Limit.resolveInRoot(ws, "/etc/passwd"));
        assertEquals(root.resolve("a.txt").toString(), Limit.resolveInRoot(ws, root.resolve("a.txt").toString()));
        assertEquals(root.resolve("s/t.txt").toString(), Limit.resolveInRoot(ws, "s\\t.txt"));
        assertThrows(ToolException.class, () -> Limit.resolveInRoot(ws, "..\\secret"));
        // 指向根外的软链被拦
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("s.txt"), "secret");
        Files.createSymbolicLink(root.resolve("link"), outside);
        ToolException e = assertThrows(ToolException.class, () -> Limit.resolveInRoot(ws, "link/s.txt"));
        assertTrue(e.getMessage().contains("链接"));
        // 缓冲区地址原样放行
        assertEquals("buffer:*scratch*", Limit.resolveInRoot(ws, "buffer:*scratch*"));
    }

    @Test
    void pathContains() {
        String root = "/r/o";
        assertTrue(Limit.pathContains(root, "a"));
        assertTrue(Limit.pathContains(root, "sub/../a"));
        assertTrue(Limit.pathContains(root, root));
        assertFalse(Limit.pathContains(root, "../x"));
        assertFalse(Limit.pathContains(root, "/r/other"));
        assertFalse(Limit.pathContains(root, "/r/oo"));
    }

    @Test
    void tags() {
        assertEquals("4F9F2CAB", Tags.contentTag("hello"));
        assertEquals("811C9DC5", Tags.contentTag(""));
        assertEquals(Tags.contentTag("中文"), Tags.contentTag("中文"));
        assertNotEquals(Tags.contentTag("hello"), Tags.contentTag("hellp"));
        assertEquals("ABCD1234", Tags.normalize("[foo.c#abcd1234]"));
        assertEquals("ABCD1234", Tags.normalize("  #abcd1234 "));
        assertEquals("ABCD1234", Tags.normalize("abcd1234"));
        assertTrue(Tags.matches("[x#4f9f2cab]", "4F9F2CAB"));
    }

    @Test
    void seen() {
        Seen s = new Seen();
        assertTrue(s.check("a", "/a", "x", "").contains("还没有读过"));
        s.note("/a", "x");
        assertNull(s.check("a", "/a", "x", ""));
        assertTrue(s.check("a", "/a", "y", "").contains("变了"));
        assertTrue(s.check("a", "/a", "x", "DEADBEEF").contains("你给的 TAG 是 DEADBEEF"));
        assertNull(s.check("a", "/a", "x", "[a#" + Tags.contentTag("x") + "]"));
        s.reset();
        assertNull(s.tag("/a"));
    }

    @Test
    void balanceChecker() {
        BalanceChecker c = new BalanceChecker();
        assertNull(c.problems("a.txt", "((("));
        assertEquals(List.of(), c.problems("a.c", "int f() { return g(1, \"(\", ')'); }"));
        assertEquals(1, c.problems("a.c", "int f() { return 1;").size());
        assertEquals(List.of(), c.problems("a.rs", "fn f<'a>(x: &'a str) -> char { 'x' }"));
        assertEquals(List.of(), c.problems("a.py", "s = '''(\n'''\nx = [1, 2]  # )"));
        assertEquals(List.of(), c.problems("a.ss", "(define c #\\() ; )\n#| ( |#\n(display \")\")"));
        assertFalse(c.problems("a.ss", "(define (f x) x").isEmpty());
        assertFalse(c.problems("a.js", "let s = \"abc;\nf(1)").isEmpty());
        assertEquals(List.of(), c.problems("a.js", "const t = `a ${x} (`; f(1)"));
        assertTrue(c.problems("a.ts", "f(1))").getFirst().contains("多出来"));
    }

    @Test
    void checkNotWorse() {
        Workspace ws = new Workspace(tmp);
        assertNull(Check.notWorse(ws, "a.c", "int x;", "int y;"));
        String w = Check.notWorse(ws, "a.c", "f();", "f(;");
        assertNotNull(w);
        assertTrue(w.contains("解析不了") && w.contains("没有写入"));
        // 坏的改得更坏 → 拒；正在修 → 放行
        assertTrue(Check.notWorse(ws, "a.c", "f(", "f((").contains("从 1 处变成了 2 处"));
        assertNull(Check.notWorse(ws, "a.c", "f(((", "f(("));
        assertNull(Check.notWorse(ws, "a.txt", "", "((("));
        assertEquals("✓ 改完之后语法没问题", Check.previewNote(ws, "a.c", "", "f();"));
        assertNull(Check.previewNote(ws, "a.txt", "", "x"));
        assertTrue(Check.previewNote(ws, "a.c", "f();", "f(;").startsWith("⚠️ 批准之后这次调用**会被拒**"));
        assertTrue(Check.previewNote(ws, "a.c", "f(", "f( ").contains("仍然有 1 处"));
        // 检查器抛异常 → 不拦
        ws.put(Checker.class, (p, t) -> { throw new IllegalStateException("boom"); });
        assertNull(Check.notWorse(ws, "a.c", "f();", "f(;"));
        ws.put(Checker.class, Checker.NONE);
        assertNull(Check.notWorse(ws, "a.c", "f();", "f(;"));
    }

    @Test
    void argsChecker() {
        AgentTool t = FileToolset.readFile();
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        in.put("path", "a.txt");
        in.put("offset", 3);
        assertNull(ArgsChecker.check(t, in));
        in.put("offset", "3");
        String m = ArgsChecker.check(t, in);
        assertTrue(m.startsWith("工具 read-file 的参数类型不对：offset 要 整数，收到的是 字符串（\"3\"）"), m);
        in.put("offset", 1.5);
        assertTrue(ArgsChecker.check(t, in).contains("收到的是 小数"));
        in.remove("offset");
        in.putNull("limit");
        assertTrue(ArgsChecker.check(t, in).contains("limit 传了 null"));
        in.remove("limit");
        in.put("extra", 1);   // 没声明的键不管
        assertNull(ArgsChecker.check(t, in));
        ObjectNode w = JsonNodeFactory.instance.objectNode();
        w.put("path", "x");
        w.put("content", "y".repeat(100));
        w.put("tag", 5);
        String m2 = ArgsChecker.check(FileToolset.writeFile(), w);
        assertTrue(m2.contains("tag 要 字符串，收到的是 整数（5）"), m2);
    }

    @Test
    void diff() {
        assertEquals("", Diff.unified("a\nb\n", "a\nb\n", "现在", "批准之后", 3));
        String d = Diff.unified("a\nb\nc\n", "a\nB\nc\n", "现在", "批准之后", 3);
        assertEquals("--- 现在\n+++ 批准之后\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c", d);
        assertEquals("--- 现在\n+++ 批准之后\n@@ -0,0 +1,2 @@\n+x\n+y", Diff.unified("", "x\ny\n", "现在", "批准之后", 3));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 30; i++) big.append("l").append(i).append('\n');
        String after = big.toString().replace("l2\n", "L2\n").replace("l25\n", "L25\n");
        String d2 = Diff.unified(big.toString(), after, "a", "b", 3);
        assertEquals(2, count(d2, "@@ -"));   // 相隔远的两处是两块
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }
}
