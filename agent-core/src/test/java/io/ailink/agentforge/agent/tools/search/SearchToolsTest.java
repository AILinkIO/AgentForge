package io.ailink.agentforge.agent.tools.search;

import io.ailink.agentforge.agent.tools.Limit;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.ToolOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.ailink.agentforge.agent.util.ToolKit.call;
import static io.ailink.agentforge.agent.util.ToolKit.put;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class SearchToolsTest {
    @TempDir Path root;

    @BeforeEach
    void setup() throws Exception {
        Files.createDirectories(root.resolve(".git"));      // 当成 git 仓
        put(root.resolve(".gitignore"), "build/\n*.log\n!keep.log\n");
        put(root.resolve("src.txt"), "hello world\nsecond line\n(define (f x)\n");
        put(root.resolve("sub/other.txt"), "Hello again\nnothing\nhello third\n");
        put(root.resolve("sub/deep/a.ss"), "(define x 1)\n");
        put(root.resolve("sub/deep/b.c"), "int main() { return 0; }\n");
        put(root.resolve("build/out.txt"), "hello from build\n");
        put(root.resolve("x.log"), "hello log\n");
        put(root.resolve("keep.log"), "hello keep\n");
    }

    private String text(ToolOutput o) {
        assertFalse(o.error(), o.text());
        return o.text();
    }

    // ── glob / walker 纯函数 ──

    @Test
    void globExpand() {
        assertEquals(List.of("*.ss"), Glob.expand("*.ss"));
        assertEquals(List.of("*.c", "*.h"), Glob.expand("*.{c,h}"));
        assertEquals(List.of("a.x", "a.y", "b.x", "b.y"), Glob.expand("{a,b}.{x,y}"));
        assertEquals(List.of("*.{c"), Glob.expand("*.{c"));
    }

    @Test
    void globHit() {
        assertTrue(Glob.hit(List.of("*.ss"), "a/b/c.ss"), "不带 / 按基名");
        assertFalse(Glob.hit(List.of("*.c"), "a/b.ss"));
        assertTrue(Glob.hit(List.of("coxn/*.ss"), "coxn/agent/tools/file.ss"), "带 / 对整条，* 跨 /");
        assertFalse(Glob.hit(List.of("coxn/*.ss"), "other/a.ss"));
        assertTrue(Glob.hit(List.of("*.c", "*.ss"), "x.ss"));
        assertTrue(Glob.hit(List.of("test_?.py"), "test_1.py"));
        assertTrue(Glob.hit(List.of("[ab].txt"), "b.txt"));
        assertFalse(Glob.hit(List.of("[!ab].txt"), "b.txt"));
    }

    @Test
    void walkerHonorsGitignore() {
        List<String> c = FileWalker.candidates(root, "");
        assertNotNull(c);
        assertTrue(c.contains("src.txt"));
        assertTrue(c.contains("sub/deep/a.ss"));
        assertTrue(c.contains("keep.log"), "! 捞回");
        assertFalse(c.contains("x.log"));
        assertFalse(c.contains("build/out.txt"), "被忽略的目录整棵剪掉");
        assertFalse(c.stream().anyMatch(s -> s.startsWith(".git/")));
        assertEquals(List.of("sub/deep/a.ss", "sub/deep/b.c"), FileWalker.candidates(root, "sub/deep"));
        assertEquals(List.of(), FileWalker.candidates(root, "build"), "从被忽略的目录起 = 空");
    }

    @Test
    void walkerNestedGitignoreAndAnchors() throws Exception {
        put(root.resolve("sub/.gitignore"), "/deep/b.c\n**/gen/\n");
        put(root.resolve("sub/x/gen/z.txt"), "z");
        List<String> c = FileWalker.candidates(root, "");
        assertFalse(c.contains("sub/deep/b.c"));
        assertTrue(c.contains("sub/deep/a.ss"));
        assertFalse(c.contains("sub/x/gen/z.txt"));
    }

    @Test
    void notGitRepoReturnsNull(@TempDir Path plain) {
        // 临时目录的祖先里一般没有 .git
        assumeFalse(FileWalker.repoTop(plain) != null);
        assertNull(FileWalker.candidates(plain, ""));
    }

    // ── 路径限定 ──

    @Test
    void rootConfinement() throws Exception {
        assertEquals(root.resolve("a/b").toAbsolutePath().normalize(), Limit.resolveUnder(root, "a/./c/../b"));
        ToolException e = assertThrows(ToolException.class, () -> Limit.resolveUnder(root, "../etc/passwd"));
        assertTrue(e.getMessage().contains("越出了根目录"));
        assertThrows(ToolException.class, () -> Limit.resolveUnder(root, "/etc/passwd"));
        assertTrue(Limit.pathContains(root, "a/../b"));
        assertFalse(Limit.pathContains(root, "../x"));
    }

    @Test
    void symlinkEscapeIsCaught(@TempDir Path outside) throws Exception {
        assumeFalse(io.ailink.agentforge.agent.util.ToolKit.windows());
        Files.createSymbolicLink(root.resolve("link"), outside);
        ToolException e = assertThrows(ToolException.class, () -> Limit.resolveUnder(root, "link"));
        assertTrue(e.getMessage().contains("指向根目录外面的链接"));
        assertThrows(ToolException.class, () -> Limit.resolveUnder(root, "link/new-file.txt"), "不存在的文件取最深的已存在祖先");
    }

    @Test
    void truncateNotes() {
        assertEquals("abc", Limit.truncateNote("abc", 5, "x"));
        String t = Limit.truncateNote("abcdefgh", 3, "结果");
        assertTrue(t.startsWith("abc\n\n[已截断：结果 共 8 个字符，上面是前 3 个]"));
    }

    @Test
    void truncateSpillWritesFullCopy(@TempDir Path spill) throws Exception {
        Limit.spillDir(spill);
        try {
            String big = "x".repeat(100) + "TAIL";
            String t = Limit.truncateNoteSpill(big, 10, "stdout");
            assertTrue(t.contains("完整内容已存到"));
            String path = t.substring(t.indexOf("已存到 ") + 4, t.indexOf(" ——"));
            assertEquals(big, Files.readString(Path.of(path)));
        } finally {
            Limit.spillDir(null);
        }
    }

    // ── search-text ──

    @Test
    void searchBasics() {
        String r = text(call(SearchTools.searchText(), root, "pattern", "hello"));
        assertTrue(r.startsWith("找到 3 处命中\n"), r);
        assertTrue(r.contains("src.txt:1:hello world"));
        assertTrue(r.contains("sub/other.txt:3:hello third"), "跨子目录递归");
        assertTrue(r.contains("keep.log:1:"));
        assertFalse(r.contains("build/out.txt"), "默认不搜忽略的");
        assertFalse(r.contains("x.log"));
        assertFalse(r.contains(root.toString()), "不含绝对路径");
    }

    @Test
    void searchAllIncludesIgnored() {
        String r = text(call(SearchTools.searchText(), root, "pattern", "hello", "all", true));
        assertTrue(r.contains("build/out.txt:1:"));
        assertTrue(r.contains("（连忽略的文件一起搜的）"));
    }

    @Test
    void searchIgnoreCaseFilesCount() {
        String r = text(call(SearchTools.searchText(), root, "pattern", "HELLO", "ignore-case", true, "output", "files"));
        assertTrue(r.startsWith("3 个文件有命中"), r);
        assertTrue(r.contains("sub/other.txt\n"));
        assertFalse(r.contains(":1:"));
        String c = text(call(SearchTools.searchText(), root, "pattern", "hello", "ignore-case", true, "output", "count"));
        assertTrue(c.contains("sub/other.txt:2"), c);
        assertFalse(c.contains(":0"), "零计数不列");
    }

    @Test
    void searchLiteralAndRegexError() {
        ToolOutput bad = call(SearchTools.searchText(), root, "pattern", "(define (f");
        assertTrue(bad.error());
        assertTrue(bad.text().contains("literal 设为 true"), bad.text());
        String r = text(call(SearchTools.searchText(), root, "pattern", "(define (f", "literal", true));
        assertTrue(r.contains("src.txt:3:(define (f x)"));
    }

    @Test
    void searchNoMatchIsNotError() {
        ToolOutput o = call(SearchTools.searchText(), root, "pattern", "zzz-not-there");
        assertFalse(o.error());
        assertTrue(o.text().startsWith("没有命中："));
    }

    @Test
    void searchContextLinesUseDash() {
        String r = text(call(SearchTools.searchText(), root, "pattern", "second", "context", 1));
        assertTrue(r.contains("src.txt-1-hello world"), r);
        assertTrue(r.contains("src.txt:2:second line"));
        assertTrue(r.contains("src.txt-3-(define (f x)"));
    }

    @Test
    void searchGlobAndPathAndPaging() {
        String g = text(call(SearchTools.searchText(), root, "pattern", "define", "glob", "*.{ss,c}"));
        assertTrue(g.contains("sub/deep/a.ss:1:"));
        assertFalse(g.contains("src.txt"));
        String p = text(call(SearchTools.searchText(), root, "pattern", "hello", "path", "sub"));
        assertTrue(p.startsWith("找到 1 处命中"), p);
        String page = text(call(SearchTools.searchText(), root, "pattern", "hello", "head-limit", 1, "offset", 1));
        assertTrue(page.contains("这里给第 2–2 条"), page);
        assertTrue(page.contains("要下一页：offset=2"));
        ToolOutput none = call(SearchTools.searchText(), root, "pattern", "x", "glob", "*.nothing");
        assertTrue(none.text().startsWith("没有可搜的文件"));
    }

    @Test
    void searchRejectsEscapeAndBadOutput() {
        assertTrue(call(SearchTools.searchText(), root, "pattern", "x", "path", "../..").error());
        ToolOutput o = call(SearchTools.searchText(), root, "pattern", "x", "output", "json");
        assertTrue(o.error());
        assertTrue(o.text().contains("output 只能是"));
    }

    @Test
    void catastrophicRegexIsRejected() throws Exception {
        put(root.resolve("evil.txt"), "a".repeat(40) + "!\n");
        ToolOutput o = call(SearchTools.searchText(), root, "pattern", "(a+)+$", "glob", "evil.txt");
        // 要么报步数超限，要么（正则引擎够聪明时）正常结束；绝不能卡死
        if (o.error()) assertTrue(o.text().contains("步数超限"), o.text());
    }

    // ── find-files ──

    @Test
    void findByGlob() {
        String r = text(call(SearchTools.findFiles(), root, "name", "*.ss"));
        assertTrue(r.startsWith("找到 1 个\n"), r);
        assertTrue(r.contains("sub/deep/a.ss"));
        String none = text(call(SearchTools.findFiles(), root, "name", "*.nope"));
        assertTrue(none.startsWith("没有找到匹配"));
    }

    @Test
    void findFuzzy() {
        String r = text(call(SearchTools.findFiles(), root, "name", "sdeepa", "fuzzy", true));
        assertTrue(r.contains("sub/deep/a.ss"), r);
        List<String> ranked = Fuzzy.filter(List.of("x/agent/tools/file.ss", "agtool.c", "zzz"), "agtool");
        assertEquals("agtool.c", ranked.getFirst());
        assertTrue(ranked.contains("x/agent/tools/file.ss"));
        assertFalse(ranked.contains("zzz"));
    }

    @Test
    void findDirectories() {
        String r = text(call(SearchTools.findFiles(), root, "name", "dee*", "type", "d"));
        assertTrue(r.contains("sub/deep"), r);
        assertTrue(call(SearchTools.findFiles(), root, "name", "x", "type", "q").error());
    }

    @Test
    void flags() {
        assertTrue(SearchTools.searchText().readOnly());
        assertTrue(SearchTools.searchText().concurrencySafe());
        assertFalse(SearchTools.findFiles().sensitive());
    }
}
