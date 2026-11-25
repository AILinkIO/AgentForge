package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tools.file.PatchTool;
import io.ailink.agentforge.agent.tools.file.PatchTool.Chunk;
import io.ailink.agentforge.agent.tools.file.PatchTool.Hunk;
import io.ailink.agentforge.agent.tools.file.PatchTool.HunkType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** apply-patch 与 delete-path / Deletion 分类器。 */
class PatchDeleteTest {

    @TempDir Path tmp;
    ToolHarness h;
    Path root;
    Path outside;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("root"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        h = new ToolHarness(root);
    }

    private static Chunk ch(List<String> o, List<String> n, String ctx, boolean eof) { return new Chunk(o, n, ctx, eof); }

    @Test
    void parse() {
        List<Hunk> hs = PatchTool.parse("*** Begin Patch\n*** Add File: hello.txt\n+Hello world\n+second\n"
                + "*** Update File: src/app.py\n*** Move to: src/main.py\n@@ def greet():\n-print(\"Hi\")\n+print(\"Hello, world!\")\n"
                + "*** Delete File: obsolete.txt\n*** End Patch");
        assertEquals(List.of(HunkType.ADD, HunkType.UPDATE, HunkType.DELETE), hs.stream().map(Hunk::type).toList());
        assertEquals("Hello world\nsecond", hs.get(0).content());
        assertEquals("src/main.py", hs.get(1).move());
        Chunk c = hs.get(1).chunks().getFirst();
        assertEquals(List.of("print(\"Hi\")"), c.oldLines());
        assertEquals(List.of("print(\"Hello, world!\")"), c.newLines());
        assertEquals("def greet():", c.context());
        assertFalse(c.eof());
        assertEquals("*** Begin Patch\n*** End Patch", PatchTool.stripHeredoc("cat <<'EOF'\n*** Begin Patch\n*** End Patch\nEOF\n"));
        assertEquals("*** Begin Patch\n*** End Patch", PatchTool.stripHeredoc("*** Begin Patch\n*** End Patch"));
        assertThrows(ToolException.class, () -> PatchTool.parse("*** Add File: x\n+1"));
        assertEquals(List.of(HunkType.ADD), PatchTool.parse("*** Begin Patch\r\n*** Add File: a\r\n+x\r\n*** End Patch\r\n").stream().map(Hunk::type).toList());
        assertEquals(List.of(), PatchTool.parse("*** Begin Patch\n*** End Patch"));
        Chunk e = PatchTool.parse("*** Begin Patch\n*** Update File: a\n@@\n-x\n+y\n*** End of File\n*** End Patch").getFirst().chunks().getFirst();
        assertTrue(e.eof());
        assertNull(e.context());
    }

    @Test
    void applyChunks() {
        String text = "a\n  b\nc\n";
        assertEquals("a\n  B\nc\n", PatchTool.applyChunks("t", text, List.of(ch(List.of("  b"), List.of("  B"), null, false))));
        assertEquals("a\nB\nc\n", PatchTool.applyChunks("t", "a\nb   \nc\n", List.of(ch(List.of("b"), List.of("B"), null, false))));
        assertEquals("a\nB\nc\n", PatchTool.applyChunks("t", text, List.of(ch(List.of("b"), List.of("B"), null, false))));
        assertEquals("ok\n", PatchTool.applyChunks("t", "say “hi”\n", List.of(ch(List.of("say \"hi\""), List.of("ok"), null, false))));
        assertEquals("x\nfn a\n  1\nfn b\n  2\n", PatchTool.applyChunks("t", "x\nfn a\n  1\nfn b\n  1\n", List.of(ch(List.of("  1"), List.of("  2"), "fn b", false))));
        assertEquals("1\n2\nz\n", PatchTool.applyChunks("t", "1\n2\n1\n", List.of(ch(List.of("1"), List.of("z"), null, true))));
        assertEquals("a\nb\n", PatchTool.applyChunks("t", "a\n", List.of(ch(List.of(), List.of("b"), null, false))));
        assertEquals("A\nb\nc\nD\n", PatchTool.applyChunks("t", "a\nb\nc\nd\n",
                List.of(ch(List.of("a"), List.of("A"), null, false), ch(List.of("d"), List.of("D"), null, false))));
        ToolException e = assertThrows(ToolException.class, () -> PatchTool.applyChunks("t", text, List.of(ch(List.of("zzz"), List.of("y"), null, false))));
        assertTrue(e.getMessage().contains("没找到"));
    }

    @Test
    void applyPatchTool() throws Exception {
        h.put("p1.txt", "one\ntwo\nthree\n");
        h.put("p2.txt", "keep\n");
        h.put("gone.txt", "bye\n");
        assertTrue(h.err("apply-patch", "patchText", "*** Begin Patch\n*** Update File: p1.txt\n@@\n-two\n+2\n*** End Patch").contains("没有读过"));
        h.ok("read-file", "path", "p1.txt");
        h.ok("read-file", "path", "p2.txt");
        h.ok("read-file", "path", "gone.txt");
        String r = h.ok("apply-patch", "patchText", "*** Begin Patch\n*** Add File: sub/new.txt\n+hello\n+world\n"
                + "*** Update File: p1.txt\n@@\n-two\n+2\n"
                + "*** Update File: p2.txt\n*** Move to: p3.txt\n@@\n-keep\n+kept\n"
                + "*** Delete File: gone.txt\n*** End Patch");
        assertTrue(r.contains("A sub/new.txt") && r.contains("M p1.txt") && r.contains("D gone.txt"), r);
        assertEquals(Limit.WINDOWS ? "hello\r\nworld\r\n" : "hello\nworld\n", h.slurp("sub/new.txt"));
        assertEquals("one\n2\nthree\n", h.slurp("p1.txt"));
        assertEquals("kept\n", h.slurp("p3.txt"));
        assertFalse(Files.exists(root.resolve("p2.txt")));
        assertFalse(Files.exists(root.resolve("gone.txt")));
        // 一处对不上 → 一个都不改
        h.put("q1.txt", "alpha\n");
        h.put("q2.txt", "beta\n");
        h.ok("read-file", "path", "q1.txt");
        h.ok("read-file", "path", "q2.txt");
        String e = h.err("apply-patch", "patchText", "*** Begin Patch\n*** Update File: q1.txt\n@@\n-alpha\n+ALPHA\n*** Update File: q2.txt\n@@\n-nope\n+x\n*** End Patch");
        assertTrue(e.contains("q2.txt"));
        assertEquals("alpha\n", h.slurp("q1.txt"));
        h.err("apply-patch", "patchText", "*** Begin Patch\n*** Update File: ../secret.txt\n@@\n-a\n+b\n*** End Patch");
        h.err("apply-patch", "patchText", "*** Begin Patch\n*** End Patch");
        h.err("apply-patch", "patchText", "  ");
    }

    @Test
    void patchPreview() throws Exception {
        h.put("pv.txt", "x\ny\n");
        h.ok("read-file", "path", "pv.txt");
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        in.put("patchText", "*** Begin Patch\n*** Update File: pv.txt\n@@\n-y\n+Y\n*** Add File: pv2.txt\n+new\n*** End Patch");
        Preview.Result pv = Preview.toolChangePreview(h.ws, "apply-patch", in);
        assertEquals(Preview.Kind.DIFF, pv.kind());
        assertTrue(pv.text().contains("M pv.txt") && pv.text().contains("A pv2.txt") && pv.text().contains("+Y"), pv.text());
        assertEquals("x\ny\n", h.slurp("pv.txt"));
        h.put("pv3.txt", "q\n");
        in.put("patchText", "*** Begin Patch\n*** Update File: pv3.txt\n@@\n-q\n+Q\n*** End Patch");
        assertTrue(Preview.toolChangePreview(h.ws, "apply-patch", in).text().contains("应用不了"));
    }

    // ── 删除 ──────────────────────────────────────────────────────────

    @Test
    void classifier() throws Exception {
        h.put("hello.txt", "hi");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(outside.resolve("secret.txt"), "s");
        assertNull(Deletion.risk(h.ws, "hello.txt"));
        assertNull(Deletion.risk(h.ws, "sub"));
        assertNull(Deletion.risk(h.ws, root.toString()));
        assertNull(Deletion.risk(h.ws, "new.txt"));
        Deletion.Risk r = Deletion.risk(h.ws, "../outside/secret.txt");
        assertEquals(Deletion.Kind.ESCAPE, r.kind());
        assertEquals(outside.resolve("secret.txt").toRealPath().toString(), r.real());
        Deletion.Risk n = Deletion.risk(h.ws, "../nope.txt");
        assertEquals(Deletion.Kind.ESCAPE, n.kind());
        assertEquals("../nope.txt", n.path());
        assertNull(n.real());
        Files.createSymbolicLink(root.resolve("escape-link"), outside);
        Deletion.Risk s = Deletion.risk(h.ws, "escape-link");
        assertEquals(Deletion.Kind.SYMLINK, s.kind());
        assertEquals(root + "/escape-link", s.path());
        assertEquals(outside.toRealPath().toString(), s.real());
        Files.createSymbolicLink(root.resolve("sub/link-in"), root.resolve("hello.txt"));
        assertEquals(Deletion.Kind.SYMLINK, Deletion.risk(h.ws, "sub/link-in").kind());
        Files.createSymbolicLink(root.resolve("dangling"), root.resolve("missing"));
        assertEquals(Deletion.Kind.UNRESOLVABLE, Deletion.risk(h.ws, "dangling").kind());
        Deletion.Risk mid = Deletion.risk(h.ws, "escape-link/secret.txt");
        assertEquals(Deletion.Kind.ESCAPE, mid.kind());
        // 一次性确认格
        Deletion d = Deletion.of(h.ws);
        assertNull(d.take());
        d.confirm("a", "b");
        assertEquals(new Deletion.Confirmed("a", "b"), d.take());
        assertNull(d.take());
    }

    @Test
    void deletePath() throws Exception {
        h.put("f.txt", "x");
        h.put("d/inner.txt", "y");
        Files.createDirectories(root.resolve("empty"));
        assertTrue(h.ok("delete-path", "path", "f.txt").startsWith("删除了 "));
        assertFalse(Files.exists(root.resolve("f.txt")));
        assertTrue(h.ok("delete-path", "path", "f.txt").contains("本来就不存在"));
        assertTrue(h.err("delete-path", "path", "d").contains("recursive=true"));
        assertTrue(Files.exists(root.resolve("d/inner.txt")));
        assertTrue(h.ok("delete-path", "path", "d", "recursive", true).contains("删除了目录"));
        h.ok("delete-path", "path", "empty");
        assertFalse(Files.exists(root.resolve("empty")));
        // 软链：没过闸 → 拒；过闸"只删链接"
        Files.writeString(outside.resolve("keep.txt"), "k");
        Files.createSymbolicLink(root.resolve("ln"), outside);
        String e = h.err("delete-path", "path", "ln");
        assertTrue(e.contains("软链接") && e.contains("gate"));
        Deletion.confirm(h.ws, "ln", root + "/ln");
        assertTrue(h.ok("delete-path", "path", "ln").contains("只删了链接本身"));
        assertFalse(Files.exists(root.resolve("ln"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(outside.resolve("keep.txt")));
        // 越界：确认的目标与现在解到的不同 → fail closed
        Deletion.confirm(h.ws, "../outside/keep.txt", "/somewhere/else");
        assertTrue(h.err("delete-path", "path", "../outside/keep.txt").contains("被改了指向"));
        assertTrue(Files.exists(outside.resolve("keep.txt")));
        // 确认格对不上号 → 当没过闸
        Deletion.confirm(h.ws, "other", "x");
        assertTrue(h.err("delete-path", "path", "../outside/keep.txt").contains("工作根目录之外"));
        String real = outside.resolve("keep.txt").toRealPath().toString();
        Deletion.confirm(h.ws, "../outside/keep.txt", real);
        assertTrue(h.ok("delete-path", "path", "../outside/keep.txt").contains("经过用户审批"));
        assertFalse(Files.exists(outside.resolve("keep.txt")));
        // 目录里的软链只删链接
        Files.writeString(outside.resolve("t.txt"), "t");
        Files.createDirectories(root.resolve("tree"));
        Files.createSymbolicLink(root.resolve("tree/out"), outside);
        h.ok("delete-path", "path", "tree", "recursive", true);
        assertTrue(Files.exists(outside.resolve("t.txt")));
    }
}
