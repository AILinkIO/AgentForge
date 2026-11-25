package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tools.file.EditTool;
import io.ailink.agentforge.agent.tools.file.SchemeForms;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** edit-file 与 list-forms / read-form / replace-form。 */
class EditFormTest {

    @TempDir Path tmp;
    ToolHarness h;

    @BeforeEach
    void setUp() { h = new ToolHarness(tmp); }

    @Test
    void pure() {
        assertEquals(1, EditTool.countOccurrences("aaa", "aa"));
        assertEquals(0, EditTool.countOccurrences("abc", ""));
        assertEquals(3, EditTool.countOccurrences("x.x.x", "x"));
        assertNull(EditTool.editApply("abc", "z", "y", false));
        assertEquals("Xbab", EditTool.editApply("abab", "a", "X", false).substring(0, 2) + "ab");
        assertEquals("XbXb", EditTool.editApply("abab", "a", "X", true));
    }

    @Test
    void edit() throws Exception {
        h.put("e.txt", "alpha\nbeta\ngamma\nbeta\n");
        assertTrue(h.err("edit-file", "path", "e.txt", "old", "alpha", "new", "A").contains("还没有读过"));
        h.ok("read-file", "path", "e.txt");
        String r = h.ok("edit-file", "path", "e.txt", "old", "gamma", "new", "G");
        assertTrue(r.contains("替换 1 处，首处在第 3 行") && r.contains("文件现在 4 行"));
        String dup = h.err("edit-file", "path", "e.txt", "old", "beta", "new", "B");
        assertTrue(dup.contains("出现了 2 次") && dup.contains("all=true"));
        assertEquals("alpha\nbeta\nG\nbeta\n", h.slurp("e.txt"));
        h.ok("edit-file", "path", "e.txt", "old", "alpha\nbeta", "new", "alpha\nB1");
        assertTrue(h.ok("edit-file", "path", "e.txt", "old", "beta", "new", "B", "all", true).contains("替换 1 处"));
        assertEquals("alpha\nB1\nG\nB\n", h.slurp("e.txt"));
        // 拿返回的 TAG 接着改，不用重读
        String r2 = h.ok("edit-file", "path", "e.txt", "old", "G", "new", "g");
        String tag = r2.substring(r2.lastIndexOf(' ') + 1);
        h.ok("edit-file", "path", "e.txt", "old", "g", "new", "gg", "tag", tag);
        assertEquals("alpha\nB1\ngg\nB\n", h.slurp("e.txt"));
        // 诊断
        assertTrue(h.err("edit-file", "path", "e.txt", "old", "alpha  B1", "new", "x").contains("只在空白上不同"));
        assertTrue(h.err("edit-file", "path", "e.txt", "old", "1:alpha\n2:B1", "new", "x").contains("行号前缀"));
        String nf = h.err("edit-file", "path", "e.txt", "old", "zzz", "new", "x");
        assertTrue(nf.contains("没找到") && !nf.contains("空白"));
        assertTrue(h.err("edit-file", "path", "e.txt", "old", "", "new", "x").contains("不能为空"));
        assertTrue(h.err("edit-file", "path", "e.txt", "old", "B", "new", "B").contains("一样"));
        assertTrue(h.err("edit-file", "path", "../e.txt", "old", "a", "new", "b").contains("越出"));
        assertTrue(h.err("edit-file", "path", "none.txt", "old", "a", "new", "b").contains("不存在"));
        h.ok("edit-file", "path", "e.txt", "old", "B1\n", "new", "");
        assertEquals("alpha\ngg\nB\n", h.slurp("e.txt"));
        // 陈旧
        h.put("e.txt", "other\n");
        assertTrue(h.err("edit-file", "path", "e.txt", "old", "other", "new", "x").contains("变了"));
    }

    @Test
    void editCrlf() throws Exception {
        h.putBytes("w.txt", "one\r\ntwo\r\nthree\r\n".getBytes());
        h.ok("read-file", "path", "w.txt");
        h.ok("edit-file", "path", "w.txt", "old", "one\r\ntwo", "new", "1\r\n2");
        assertEquals("1\r\n2\r\nthree\r\n", new String(Files.readAllBytes(tmp.resolve("w.txt"))));
        // LF 文件里的 \r 原样匹配
        h.putBytes("m.txt", "a\r\nb\nc\n".getBytes());
        h.ok("read-file", "path", "m.txt");
        h.ok("edit-file", "path", "m.txt", "old", "a\r\nb", "new", "AB");
        assertEquals("AB\nc\n", new String(Files.readAllBytes(tmp.resolve("m.txt"))));
    }

    @Test
    void editSyntaxGuard() throws Exception {
        h.put("ok.c", "int f() { return 1; }\n");
        h.ok("read-file", "path", "ok.c");
        String e = h.err("edit-file", "path", "ok.c", "old", "1; }", "new", "1;");
        assertTrue(e.contains("解析不了") && e.contains("第 1 行"));
        assertEquals("int f() { return 1; }\n", h.slurp("ok.c"));
    }

    // ── forms ─────────────────────────────────────────────────────────

    private static List<String> names(String text) {
        List<String> out = new ArrayList<>();
        for (SchemeForms.TopForm f : SchemeForms.scan(text).forms()) out.add(f.name());
        return out;
    }

    @Test
    void scanner() {
        assertEquals(List.of("a", "f"), names("(define a 1)\n(define (f x) x)"));
        assertEquals(List.of("g"), names("(define ((g a) b) a)"));
        assertEquals(List.of("m"), names("(define-syntax m (syntax-rules () [(_ x) x]))"));
        assertEquals(List.of("point"), names("(define-record-type (point make-point point?) (fields x))"));
        assertEquals(List.of("(coxn agent tools form)"), names("(library (coxn agent tools form) (export) (import))"));
        assertEquals(java.util.Arrays.asList((String) null), names("(display \"hi\")"));
        assertEquals(List.of("s", "t"), names("(define s \")(\")\n(define t 2)"));
        assertEquals(List.of("c", "t"), names("(define c #\\()\n(define t 2)"));
        assertEquals(List.of("t"), names("#| (define x |#\n(define t 2)"));
        assertEquals(List.of("t"), names("#;(define x 1)\n(define t 2)"));
        assertEquals(List.of("t"), names("#!/usr/bin/env scheme-script\n(define t 2)"));
        assertEquals(List.of("t"), names("#!chezscheme\n(define t 2)"));
        String src = ";; 头注释\n(define a 1) ; 尾注\n;; b 的注释\n(define b 2)\n";
        List<SchemeForms.TopForm> fs = SchemeForms.scan(src).forms();
        assertEquals(0, fs.get(0).start());
        assertTrue(src.substring(fs.get(1).start(), fs.get(1).end()).startsWith(";; b 的注释\n(define b"));
        SchemeForms.Result bad = SchemeForms.scan("(define a 1)\n(define (b x)\n");
        assertEquals(List.of("a"), bad.forms().stream().map(SchemeForms.TopForm::name).toList());
        assertNotNull(bad.stop());
    }

    @Test
    void formTools() throws Exception {
        String src = ";; alpha 的注释\n(define (alpha x)\n  (+ x 1))\n\n(define beta 2)\n(display \"x\")\n";
        h.put("m.ss", src);
        String lf = h.ok("list-forms", "path", "m.ss");
        String tag = Tags.contentTag(src);
        assertTrue(lf.startsWith("[m.ss#" + tag + "]\n1-3  alpha\n5-5  beta\n6-6  （匿名，用 edit-file 改）"), lf);
        String rf = h.ok("read-form", "path", "m.ss", "name", "alpha");
        assertEquals("[m.ss#" + tag + "]\n;; alpha 的注释\n(define (alpha x)\n  (+ x 1))", rf);
        assertTrue(h.err("read-form", "path", "m.ss", "name", "nope").contains("有这些：alpha、beta"));
        // list-forms 的登记就够 replace-form 用
        String r = h.ok("replace-form", "path", "m.ss", "name", "alpha", "new", "(define (alpha x) (* x 2))", "tag", tag);
        assertTrue(r.contains("已整个替换（3 行 → 1 行）"));
        assertEquals("(define (alpha x) (* x 2))\n\n(define beta 2)\n(display \"x\")\n", h.slurp("m.ss"));
        assertTrue(h.err("replace-form", "path", "m.ss", "name", "beta", "new", "(define beta").contains("解析不了"));
        assertTrue(h.err("replace-form", "path", "m.ss", "name", "beta", "new", "(a) (b)").contains("2 个顶层形式"));
        assertTrue(h.err("replace-form", "path", "m.ss", "name", "beta", "new", "  ").contains("空的"));
        assertTrue(h.err("replace-form", "path", "m.ss", "name", "beta", "new", "(x)", "tag", "DEADBEEF").contains("DEADBEEF"));
        h.put("dup.ss", "(define a 1)\n(define a 2)\n");
        h.ok("list-forms", "path", "dup.ss");
        assertTrue(h.err("replace-form", "path", "dup.ss", "name", "a", "new", "(define a 3)").contains("2 个顶层形式都叫"));
        h.put("x.c", "int x;");
        assertTrue(h.err("list-forms", "path", "x.c").contains("不是 Scheme 源文件"));
        h.put("broken.ss", "(define a 1)\n(define (b\n");
        assertTrue(h.ok("list-forms", "path", "broken.ss").contains("从第 2 行起解析不了"));
        h.put("empty.ss", ";; nothing\n");
        assertTrue(h.ok("list-forms", "path", "empty.ss").contains("（没有顶层形式）"));
    }
}
