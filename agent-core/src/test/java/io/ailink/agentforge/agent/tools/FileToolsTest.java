package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tools.file.FileTools;
import io.ailink.agentforge.agent.tools.file.FileToolset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** read-file / list-dir / write-file。 */
class FileToolsTest {

    @TempDir Path tmp;
    ToolHarness h;

    @BeforeEach
    void setUp() { h = new ToolHarness(tmp); }

    @Test
    void flagsAndOrder() {
        List<String> names = new ArrayList<>();
        List<String> sensitive = new ArrayList<>(), readOnly = new ArrayList<>(), stageable = new ArrayList<>(), conc = new ArrayList<>();
        for (AgentTool t : FileToolset.tools()) {
            names.add(t.name());
            if (t.sensitive()) sensitive.add(t.name());
            if (t.readOnly()) readOnly.add(t.name());
            if (t.stageable()) stageable.add(t.name());
            if (t.concurrencySafe()) conc.add(t.name());
        }
        assertEquals(List.of("read-file", "list-dir", "write-file", "edit-file", "delete-path", "list-forms", "read-form", "replace-form", "apply-patch"), names);
        assertEquals(List.of("write-file", "edit-file", "delete-path", "replace-form", "apply-patch"), sensitive);
        assertEquals(List.of("read-file", "list-dir", "list-forms", "read-form"), readOnly);
        assertEquals(readOnly, conc);
        assertEquals(List.of("write-file", "edit-file", "replace-form", "apply-patch"), stageable);
    }

    @Test
    void readBasics() throws Exception {
        h.put("a.txt", "one\ntwo\nthree\n");
        String r = h.ok("read-file", "path", "a.txt");
        String[] ls = r.split("\n");
        assertEquals("3 行", ls[0]);
        assertEquals("[a.txt#" + Tags.contentTag("one\ntwo\nthree\n") + "]", ls[1]);
        assertEquals("1:one", ls[2]);
        assertEquals("3:three", ls[4]);
        assertEquals(5, ls.length);
        h.put("b.txt", "x\ny\nz");
        assertTrue(h.ok("read-file", "path", "b.txt").endsWith("3:z"));
        assertEquals(Tags.contentTag("x\ny\nz"), Seen.of(h.ws).tag(h.full("b.txt")));
        assertEquals("0 行\n[e.txt#811C9DC5]\n", h.put("e.txt", "") != null ? h.ok("read-file", "path", "e.txt") : null);
    }

    @Test
    void readErrors() throws Exception {
        assertTrue(h.err("read-file", "path", "../x").contains("越出了根目录"));
        String e = h.err("read-file", "path", "nope.txt");
        assertTrue(e.contains("文件不存在：\"nope.txt\""));
        assertFalse(e.contains(tmp.toString()));
        Files.createDirectories(tmp.resolve("d"));
        assertTrue(h.err("read-file", "path", "d").contains("用 list-dir"));
        h.put("f.txt", "x");
        assertTrue(h.err("list-dir", "path", "f.txt").contains("用 read-file"));
        // 相近的名字
        h.put("src/parser.c", "x");
        String m = h.err("read-file", "path", "src/parse");
        assertTrue(m.contains("你是不是想找：src/parser.c"), m);
        String m2 = h.err("read-file", "path", "zzz/q.txt");
        assertFalse(m2.contains("你是不是想找"));
    }

    @Test
    void paging() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) sb.append("line ").append(i).append('\n');
        h.put("p.txt", sb.toString());
        String r = h.ok("read-file", "path", "p.txt", "offset", 10, "limit", 5);
        assertTrue(r.startsWith("第 10–14 行（共 100 行）\n[p.txt#"));
        assertTrue(r.contains("\n10:line 10\n") && r.contains("\n14:line 14"));
        assertFalse(r.contains("\n9:") || r.contains("\n15:"));
        assertTrue(r.endsWith("…（第 10–14 行，共 100 行；用 offset=15 继续）"));
        String last = h.ok("read-file", "path", "p.txt", "offset", 96);
        assertTrue(last.endsWith("…（第 96–100 行，共 100 行）"));
        assertFalse(last.contains("继续"));
        assertTrue(h.err("read-file", "path", "p.txt", "offset", 101).contains("共 100 行"));
        assertTrue(h.err("read-file", "path", "p.txt", "offset", -1).contains("负数"));
        assertEquals(h.ok("read-file", "path", "p.txt"), h.ok("read-file", "path", "p.txt", "offset", 0, "limit", 0));
        assertEquals(h.ok("read-file", "path", "p.txt"), h.ok("read-file", "path", "p.txt", "offset", 1, "limit", 1000));
    }

    @Test
    void offsetPastEndDoesNotRegisterSeen() throws Exception {
        h.put("s.txt", "a\n");
        h.err("read-file", "path", "s.txt", "offset", 5);
        assertNull(Seen.of(h.ws).tag(h.full("s.txt")));
    }

    @Test
    void budgetCutsOnLineBoundary() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 3000; i++) sb.append("x".repeat(40)).append('\n');
        h.put("big.txt", sb.toString());
        String r = h.ok("read-file", "path", "big.txt");
        assertTrue(r.contains("字符封顶，用 offset="), r.substring(r.length() - 200));
        int at = r.lastIndexOf("用 offset=") + "用 offset=".length();
        int e = at;
        while (Character.isDigit(r.charAt(e))) e++;
        int next = Integer.parseInt(r.substring(at, e));
        assertTrue(next > 1 && next <= 1500);
        String cont = h.ok("read-file", "path", "big.txt", "offset", next);
        assertTrue(cont.split("\n")[2].startsWith(next + ":"));
    }

    @Test
    void longLineClipped() throws Exception {
        h.put("l.txt", "y".repeat(3000) + "\nshort\n");
        String r = h.ok("read-file", "path", "l.txt");
        assertTrue(r.contains("1:" + "y".repeat(2000) + "…（本行已截断至 2000 字符）\n2:short"));
    }

    @Test
    void sniff() {
        assertEquals(FileTools.Mime.PNG, FileTools.sniffMime(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}));
        assertEquals(FileTools.Mime.JPEG, FileTools.sniffMime(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}));
        assertEquals(FileTools.Mime.WEBP, FileTools.sniffMime("RIFF....WEBP".getBytes()));
        assertNull(FileTools.sniffMime("RIFF....WAVE".getBytes()));
        assertEquals(FileTools.Mime.PDF, FileTools.sniffMime("%PDF-1.4".getBytes()));
        assertNull(FileTools.sniffMime(new byte[]{1}));
        assertNull(FileTools.sniffMime(new byte[0]));
        assertNull(FileTools.sniffMime(null));
    }

    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, (byte) 0xFF, (byte) 0xFE};

    @Test
    void readImageAndPdf() throws Exception {
        h.putBytes("pic.txt", PNG);
        List<Block> got = new ArrayList<>();
        h.env = h.env.withService(ToolEnv.Attacher.class, (n, b) -> got.add(b));
        ToolOutput o = h.call("read-file", "path", "pic.txt");
        assertFalse(o.error());
        assertTrue(o.content().get(0) instanceof Block.Text t && t.text().contains("[图片 pic.txt：image/png"));
        assertTrue(o.content().get(1) instanceof Block.Image i && i.mediaType().equals("image/png") && i.data().startsWith("iVBOR"));
        assertEquals(1, got.size());
        assertNotNull(Seen.of(h.ws).tag(h.full("pic.txt")));

        h.putBytes("doc.pdf", "%PDF-1.4\n\u0000binary".getBytes(StandardCharsets.ISO_8859_1));
        ToolOutput p = h.call("read-file", "path", "doc.pdf");
        assertTrue(p.content().get(1) instanceof Block.Document d && d.mediaType().equals("application/pdf") && d.data().startsWith("JVBERi0"));
        h.env = h.env.withService(FileTools.DocumentSupport.class, () -> false);
        ToolOutput p2 = h.call("read-file", "path", "doc.pdf");
        assertFalse(p2.error());
        assertTrue(p2.text().contains("run-command") && p2.text().contains("skill"));

        h.putBytes("v.mp4", new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p'});
        assertTrue(h.ok("read-file", "path", "v.mp4").contains("ffprobe"));
        h.putBytes("x.bin", new byte[]{1, 2, 0, 3});
        String b = h.ok("read-file", "path", "x.bin");
        assertTrue(b.contains("二进制") && b.contains("ffmpeg"));
        assertNull(Seen.of(h.ws).tag(h.full("x.bin")));
        h.put("fake.png", "just text\n");
        assertTrue(h.ok("read-file", "path", "fake.png").contains("1:just text"));
    }

    @Test
    void listDir() throws Exception {
        h.put("b.txt", "");
        h.put("a/x.txt", "");
        String r = h.ok("list-dir", "path", ".");
        assertEquals("2 项\na/\nb.txt", r);
        Files.createDirectories(tmp.resolve("empty"));
        assertEquals("（空目录）", h.ok("list-dir", "path", "empty"));
    }

    @Test
    void writeFile() throws Exception {
        String r = h.ok("write-file", "path", "sub/dir/new.txt", "content", "hello\n");
        assertTrue(r.startsWith("已新建 \"sub/dir/new.txt\"（6 个字符）。新的 TAG 是 " + Tags.contentTag("hello\n")));
        assertEquals("hello\n", h.slurp("sub/dir/new.txt"));
        // 刚写完接着覆盖，不用先读
        assertTrue(h.ok("write-file", "path", "sub/dir/new.txt", "content", "again\n").startsWith("已覆盖"));
        // 没读过的已有文件不许覆盖
        h.put("old.txt", "keep\n");
        String e = h.err("write-file", "path", "old.txt", "content", "x");
        assertTrue(e.contains("还没有读过"));
        assertEquals("keep\n", h.slurp("old.txt"));
        h.ok("read-file", "path", "old.txt");
        h.put("old.txt", "changed behind\n");
        assertTrue(h.err("write-file", "path", "old.txt", "content", "x").contains("变了"));
        h.ok("read-file", "path", "old.txt");
        assertTrue(h.err("write-file", "path", "old.txt", "content", "x", "tag", "DEADBEEF").contains("DEADBEEF"));
        h.ok("write-file", "path", "old.txt", "content", "new\n", "tag", Tags.contentTag("changed behind\n"));
        assertEquals("new\n", h.slurp("old.txt"));
        // 目录
        Files.createDirectories(tmp.resolve("dd"));
        assertTrue(h.err("write-file", "path", "dd", "content", "x").contains("是一个目录"));
        assertTrue(h.err("write-file", "path", "../o.txt", "content", "x").contains("越出"));
        assertFalse(Files.exists(tmp.resolveSibling("o.txt")));
        // 新建一个语法坏的文件 → 拒
        assertTrue(h.err("write-file", "path", "bad.c", "content", "int f() {").contains("没有写入"));
        assertFalse(Files.exists(tmp.resolve("bad.c")));
    }

    @Test
    void writeKeepsBomAndCrlfAndRefusesBinary() throws Exception {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        h.putBytes("bom.txt", concat(bom, "a\r\nb\r\n".getBytes()));
        h.ok("read-file", "path", "bom.txt");
        h.ok("write-file", "path", "bom.txt", "content", "x\ny\n");
        assertArrayEquals(concat(bom, "x\r\ny\r\n".getBytes()), Files.readAllBytes(tmp.resolve("bom.txt")));
        h.putBytes("bin.dat", new byte[]{1, 0, 2});
        h.ok("read-file", "path", "bin.dat");
        assertTrue(h.err("write-file", "path", "bin.dat", "content", "x").contains("不是 UTF-8"));
        assertArrayEquals(new byte[]{1, 0, 2}, Files.readAllBytes(tmp.resolve("bin.dat")));
    }

    @Test
    void writeFailureKeepsSeen() throws Exception {
        h.put("w.txt", "a\n");
        h.ok("read-file", "path", "w.txt");
        String before = Seen.of(h.ws).tag(h.full("w.txt"));
        Vfs.install(h.ws, new Vfs() {
            public FileText read(String p) throws java.io.IOException { return Vfs.DISK.read(p); }
            public void write(String p, String t, FileText.Eol e, FileText.Coding c, boolean b) throws java.io.IOException { throw new java.io.IOException("disk full"); }
            public boolean exists(String p) { return Vfs.DISK.exists(p); }
        });
        assertTrue(h.call("write-file", "path", "w.txt", "content", "b\n").error());
        assertEquals(before, Seen.of(h.ws).tag(h.full("w.txt")));
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }
}
