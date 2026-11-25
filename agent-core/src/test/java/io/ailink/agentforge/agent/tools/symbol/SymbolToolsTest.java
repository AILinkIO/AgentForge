package io.ailink.agentforge.agent.tools.symbol;

import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static io.ailink.agentforge.agent.util.ToolKit.call;
import static io.ailink.agentforge.agent.util.ToolKit.put;
import static org.junit.jupiter.api.Assertions.*;

class SymbolToolsTest {
    @TempDir Path root;

    @BeforeEach
    void setup() throws Exception {
        Files.createDirectories(root.resolve(".git"));
        put(root.resolve("src/Foo.java"), """
                package x;
                public class Foo {
                    private int n;
                    public static int compute(int a, int b) {
                        if (a > b) {
                            return a;
                        }
                        return helper(b);
                    }
                    static int helper(int z) { return z; }
                }
                """);
        put(root.resolve("coxn/store.ss"), """
                (library (coxn store)
                  (export put!)
                  (import (chezscheme))
                  (define (put! k v)
                    (display "(")
                    k)
                  (define limit 10))
                """);
        put(root.resolve("coxn/user.ss"), """
                (library (coxn user)
                  (export go)
                  (import (chezscheme)
                          (coxn store))
                  (define (go) (put! 1 2)))
                """);
        put(root.resolve("tests/store-test.ss"), """
                (import (chezscheme) (coxn user))
                (go)
                """);
    }

    private List<SymbolIndex.Def> defs(String rel, String text) {
        return RegexSymbolIndex.extract(rel, RegexSymbolIndex.langOf(rel), text);
    }

    private String names(List<SymbolIndex.Def> ds) {
        return ds.stream().map(d -> d.name() + ":" + d.kind()).collect(Collectors.joining(","));
    }

    @Test
    void extractPerLanguage() {
        assertEquals("Foo:class,compute:method,helper:method",
                names(defs("A.java", "public class Foo {\n  public static int compute(int a) {\n    return foo(a);\n  }\n  int helper() { return 1; }\n}\n")));
        assertEquals("Bar:class,run:function,Alias:type",
                names(defs("a.kt", "data class Bar(val x: Int)\nfun run(a: Int): Int {\n}\ntypealias Alias = String\n")));
        assertEquals("f:function,C:class,T:type,g:function",
                names(defs("a.ts", "export function f(x) {}\nclass C {}\nexport interface T {}\nconst g = (a) => a\n")));
        assertEquals("top:function,K:class,meth:method",
                names(defs("a.py", "def top():\n    pass\nclass K:\n    def meth(self):\n        return 1\n")));
        assertEquals("main:function,Serve:method,T:type",
                names(defs("a.go", "func main() {\n}\nfunc (s *S) Serve() {}\ntype T struct{}\n")));
        assertEquals("go:function,S:type,m:macro",
                names(defs("a.rs", "pub fn go() {}\nstruct S;\nmacro_rules! m { () => {} }\n")));
        assertEquals("MAX:macro,Point:type,area:function",
                names(defs("a.c", "#define MAX 3\nstruct Point {\n};\nint area(struct Point *p)\n{\n  return 0;\n}\n")));
        assertEquals("my.ns:module,f:function,m:macro,v:variable",
                names(defs("a.clj", "(ns my.ns)\n(defn f [x] x)\n(defmacro m [] nil)\n(def v 1)\n")));
        assertEquals("(a b):module,f:function,x:variable,stx:macro",
                names(defs("a.ss", "(library (a b)\n(define (f x) x)\n(define x 1)\n(define-syntax stx (syntax-rules ()))\n")));
        assertEquals("go:function", names(defs("a.lisp", "(defun go (x) x)\n")));
    }

    @Test
    void endRows() {
        List<SymbolIndex.Def> j = defs("A.java", "class A {\n  void f() {\n    if (x) { y(); }\n    String s = \"}\";\n  }\n}\n");
        assertEquals(4, j.get(1).endRow(), "字符串里的 } 不算");
        List<SymbolIndex.Def> s = defs("a.ss", "(define (f x)\n  (display \")\")\n  #\\(\n  x)\n(define y 2)\n");
        assertEquals(3, s.getFirst().endRow());
        List<SymbolIndex.Def> p = defs("a.py", "def f():\n    a = 1\n\n    return a\nx = 2\n");
        assertEquals(3, p.getFirst().endRow());
    }

    @Test
    void findSymbolWithSource() {
        ToolOutput o = call(SymbolTools.findSymbol(), root, "name", "compute", "source", true);
        assertFalse(o.error(), o.text());
        assertTrue(o.text().startsWith("找到 1 处定义\n"), o.text());
        assertTrue(o.text().contains("src/Foo.java:4:23  compute  [method]"), o.text());
        assertTrue(o.text().contains("4|     public static int compute"));
        assertTrue(o.text().contains("9|     }"));
        assertFalse(o.text().contains("10|"));
    }

    @Test
    void findSymbolEmptyAndShort() {
        String none = call(SymbolTools.findSymbol(), root, "name", "zzzzz").text();
        assertTrue(none.startsWith("没有找到名为 \"zzzzz\" 的定义"), none);
        assertTrue(call(SymbolTools.findSymbol(), root, "name", "q").text().contains("太短"));
    }

    @Test
    void symbolRefs() {
        String r = call(SymbolTools.symbolRefs(), root, "name", "helper").text();
        assertTrue(r.startsWith("1 处引用\n"), r);
        assertTrue(r.contains("src/Foo.java:8:16  [call]  in compute"), r);
        String s = call(SymbolTools.symbolRefs(), root, "name", "put!").text();
        assertTrue(s.contains("coxn/user.ss:5:17  [call]  in go"), s);
        assertTrue(call(SymbolTools.symbolRefs(), root, "name", "nobody").text().startsWith("没有查到"));
    }

    @Test
    void impactFollowsImports() {
        String m = call(SymbolTools.impact(), root, "name", "(coxn store)").text();
        assertTrue(m.startsWith("2 个文件受影响（2 跳以内）\n"), m);
        assertTrue(m.contains("── 第 1 跳（1 个）\n  coxn/user.ss"));
        assertTrue(m.contains("── 第 2 跳（1 个）\n  tests/store-test.ss"));
        String f = call(SymbolTools.impact(), root, "name", "coxn/store.ss", "tests-only", true).text();
        assertTrue(f.startsWith("1 个受影响的测试文件（共 2 个文件受影响）\ntests/store-test.ss"), f);
        assertTrue(call(SymbolTools.impact(), root, "name", "nowhere/x.ss").text().contains("推不出模块名"));
        String t = call(SymbolTools.impact(), root, "name", "(coxn store)", "tests-only", true).text();
        assertTrue(t.startsWith("1 个受影响的测试文件（共 2 个文件受影响）\ntests/store-test.ss"), t);
    }

    @Test
    void injectedIndexIsUsed() {
        Workspace ws = new Workspace(root);
        ws.put(SymbolIndex.class, new SymbolIndex() {
            @Override public boolean enabled() { return true; }

            @Override public Status status() {
                return new Status(true, true, true, true, 3, 9, List.of("X"), false, RefsMode.OFF);
            }

            @Override public List<Def> find(String name, int limit) { return List.of(); }

            @Override public List<Ref> refs(String name) { return List.of(); }
        });
        assertFalse(SymbolTools.symbolRefs().enabled(ws), "引用边关着 → symbol-refs 不出现");
        assertTrue(SymbolTools.findSymbol().enabled(ws));
        String r = call(SymbolTools.findSymbol(), io.ailink.agentforge.agent.tool.ToolEnv.of(ws), "name", "abc").text();
        assertTrue(r.contains("索引还在建（3/9）"), r);
    }

    @Test
    void flags() {
        for (var t : SymbolTools.tools()) {
            assertTrue(t.readOnly(), t.name());
            assertFalse(t.sensitive());
        }
    }
}
