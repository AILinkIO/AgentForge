package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.attach.Attach;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** staging / preview / attach。 */
class StagingAttachTest {

    @TempDir Path tmp;
    ToolHarness h;

    @BeforeEach
    void setUp() { h = new ToolHarness(tmp); }

    @Test
    void stagingReadOwnWritesAndCommit() throws Exception {
        h.put("a.txt", "v1\n");
        h.ok("read-file", "path", "a.txt");
        Staging st = Staging.begin(h.ws);
        h.ok("edit-file", "path", "a.txt", "old", "v1", "new", "v2");
        assertEquals("v1\n", h.slurp("a.txt"));                       // 盘上没动
        assertTrue(h.ok("read-file", "path", "a.txt").contains("1:v2"));   // 读己之所写
        h.ok("edit-file", "path", "a.txt", "old", "v2", "new", "v3");
        h.ok("write-file", "path", "new/b.txt", "content", "b\n");
        assertTrue(h.ok("read-file", "path", "new/b.txt").contains("1:b"));
        assertFalse(Files.exists(tmp.resolve("new/b.txt")));
        List<Staging.Change> ps = st.pending();
        assertEquals(List.of(h.full("a.txt"), h.full("new/b.txt")), ps.stream().map(Staging.Change::path).toList());
        assertEquals("v1\n", ps.get(0).before());                    // before 只记第一次
        assertEquals("v3\n", ps.get(0).after());
        assertFalse(ps.get(1).existed());
        assertEquals(1, st.commit(h.full("a.txt")));
        assertEquals("v3\n", h.slurp("a.txt"));
        assertEquals(1, st.count());
        assertEquals(1, st.discard(null));
        assertTrue(st.isEmpty());
        Staging.end(h.ws);
        assertSame(Vfs.DISK, Vfs.of(h.ws));
        assertFalse(Files.exists(tmp.resolve("new/b.txt")));
    }

    @Test
    void stagingConcurrentWrites() throws Exception {
        Staging st = new Staging(Vfs.DISK);
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int k = i;
            fs.add(ex.submit(() -> { st.write(tmp.resolve("f" + (k % 20)).toString(), "x" + k, FileText.Eol.LF, FileText.Coding.UTF_8, false); return null; }));
        }
        for (Future<?> f : fs) f.get();
        ex.shutdown();
        assertEquals(20, st.count());
        assertEquals(20, st.commit(null));
        assertEquals(0, st.count());
    }

    @Test
    void preview() throws Exception {
        h.put("p.c", "int f() { return 1; }\n");
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        in.put("path", "p.c");
        in.put("content", "int f() { return 2; }\n");
        Preview.Result r = Preview.toolChangePreview(h.ws, "write-file", in);
        assertEquals(Preview.Kind.DIFF, r.kind());
        assertTrue(r.text().startsWith("write-file 覆盖 " + h.full("p.c") + "\n--- 现在\n+++ 批准之后"));
        assertTrue(r.text().endsWith("✓ 改完之后语法没问题"));
        in.put("content", "int f() { return 1; }\n");
        assertTrue(Preview.toolChangePreview(h.ws, "write-file", in).text().contains("不会改变文件内容"));
        ObjectNode ed = JsonNodeFactory.instance.objectNode();
        ed.put("path", "p.c");
        ed.put("old", "1; }");
        ed.put("new", "1;");
        assertTrue(Preview.toolChangePreview(h.ws, "edit-file", ed).text().contains("会被拒"));
        ed.put("old", "zzz");
        assertTrue(Preview.toolChangePreview(h.ws, "edit-file", ed).text().contains("找不到要替换的原文"));
        ed.put("path", "missing.c");
        assertTrue(Preview.toolChangePreview(h.ws, "edit-file", ed).text().contains("不存在"));
        ObjectNode rc = JsonNodeFactory.instance.objectNode();
        rc.put("command", "rm -rf /tmp/x");
        Preview.Result c = Preview.toolChangePreview(h.ws, "run-command", rc);
        assertEquals(Preview.Kind.TEXT, c.kind());
        assertTrue(c.text().endsWith("rm -rf /tmp/x"));
        assertNull(Preview.toolChangePreview(h.ws, "read-file", rc));
        ObjectNode bad = JsonNodeFactory.instance.objectNode();
        bad.put("path", "../x");
        bad.put("content", "y");
        assertNull(Preview.toolChangePreview(h.ws, "write-file", bad));   // 越界：没有可预览的（工具体会报错）
    }

    @Test
    void attach() throws Exception {
        h.putBytes("pic.png", FileToolsTest.PNG);
        Block.Image img = Attach.attachImage(h.ws, "pic.png");
        assertEquals("image/png", img.mediaType());
        h.put("t.txt", "a\nb\n");
        assertThrows(ToolException.class, () -> Attach.attachImage(h.ws, "t.txt"));
        Attach.Result t = Attach.attachFile(h.ws, "t.txt");
        assertEquals(Attach.Kind.TEXT, t.kind());
        assertEquals("2 行\n[t.txt#" + Tags.contentTag("a\nb\n") + "]\n1:a\n2:b\n", ((Block.Text) t.block()).text());
        assertNull(Seen.of(h.ws).tag(h.full("t.txt")));               // @ 提及不登记
        assertEquals(Attach.Kind.IMAGE, Attach.attachFile(h.ws, "pic.png").kind());
        h.putBytes("b.bin", new byte[]{1, 0, 1});
        Attach.Result b = Attach.attachFile(h.ws, "b.bin");
        assertEquals(Attach.Kind.FAILED, b.kind());
        assertTrue(b.reason().contains("NUL"));
        Files.createDirectories(tmp.resolve("dir/x"));
        assertEquals(Attach.Kind.DIRECTORY, Attach.attachFile(h.ws, "dir").kind());
        assertTrue(Attach.attachFile(h.ws, "nope").reason().contains("文件不存在"));
        h.putBytes("d.pdf", "%PDF-1.4 x".getBytes());
        assertTrue(Attach.attachFile(h.ws, "d.pdf").reason().contains("PDF"));
    }
}
