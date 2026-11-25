package io.ailink.agentforge.agent.codemode;

import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 对应 coxswain {@code tests/agent-codemode-test.ss}，外加解释器本身的覆盖。 */
class CodeModeTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static CodeModeResult run(String code) { return CodeMode.run(code, null); }

    static String val(String code) {
        CodeModeResult r = run(code);
        assertTrue(r.ok(), () -> code + " → " + r.error());
        return r.value();
    }

    static String err(String code) {
        CodeModeResult r = run(code);
        assertFalse(r.ok(), () -> code + " 应当出错，却得到 " + r.value());
        return r.error();
    }

    // ── 跑得起来 ─────────────────────────────────────────────────────────

    @Test
    void basics() {
        assertEquals("3", val("(+ 1 2)"));
        assertEquals("(1 4 9)", val("(define (sq x) (* x x)) (map sq '(1 2 3))"));
        assertEquals("ab", val("(string-append \"a\" \"b\")"));
        assertEquals("a-b-c", val("(string-join (string-split \"a,b,c\" \",\") \"-\")"));
        assertEquals("7", val("(ref '((\"k\" . 7)) \"k\")"));
        assertEquals("#(1 2)", val("(ref (json-parse \"{\\\"x\\\": [1,2]}\") \"x\")"));
        assertEquals("1-\"x\"", val("(format \"~a-~s\" 1 \"x\")"));
        assertEquals(List.of("hi 1", "two"), run("(log! \"hi ~a\" 1) (log! \"two\") 0").logs());
        assertEquals("", val("(if #f #f)"));
        assertFalse(run("sq").ok(), "define 不跨次残留");
    }

    @Test
    void languageCoverage() {
        assertEquals("120", val("(define (f n) (if (= n 0) 1 (* n (f (- n 1))))) (f 5)"));
        assertEquals("55", val("(let loop ([i 0] [acc 0]) (if (> i 10) acc (loop (+ i 1) (+ acc i))))"));
        assertEquals("3", val("(let* ((x 1) (y (+ x 1))) (+ x y))"));
        assertEquals("#t", val("(letrec ((ev? (lambda (n) (if (= n 0) #t (od? (- n 1))))) (od? (lambda (n) (if (= n 0) #f (ev? (- n 1)))))) (ev? 100))"));
        assertEquals("big", val("(cond ((< 5 3) 'small) ((> 5 3) 'big) (else 'eq))"));
        assertEquals("2", val("(cond ((assv 2 '((1 . a) (2 . b))) => (lambda (p) (car p))) (else #f))"));
        assertEquals("vowel", val("(case #\\a ((#\\a #\\e) 'vowel) (else 'other))"));
        assertEquals("#f", val("(and 1 #f 3)"));
        assertEquals("1", val("(or #f 1)"));
        assertEquals("yes", val("(when (> 1 0) 'yes)"));
        assertEquals("", val("(unless (> 1 0) 'no)"));
        assertEquals("#(0 1 2 3 4)", val("(do ((v (make-vector 5)) (i 0 (+ i 1))) ((= i 5) v) (vector-set! v i i))"));
        assertEquals("(1 2 3 4)", val("(let ((x 2) (l '(3 4))) `(1 ,x ,@l))"));
        assertEquals("3", val("(define x 1) (set! x 3) x"));
        assertEquals("(1 (2 3))", val("((lambda (a . rest) (list a rest)) 1 2 3)"));
        assertEquals("2", val("(define f (case-lambda ((a) 1) ((a b) 2))) (f 1 2)"));
        assertEquals("3", val("(let-values (((a b) (values 1 2))) (+ a b))"));
        assertEquals("3", val("(call-with-values (lambda () (values 1 2)) +)"));
        assertEquals("10", val("(fold-left + 0 '(1 2 3 4))"));
        assertEquals("(1 2 3)", val("(list-sort < '(3 1 2))"));
        assertEquals("(2 4)", val("(filter even? '(1 2 3 4))"));
        assertEquals("3", val("(call/cc (lambda (k) (for-each (lambda (x) (when (> x 2) (k x))) '(1 2 3 4)) 'none))"));
        assertEquals("2", val("(define h (make-hashtable equal-hash equal?)) (hashtable-set! h \"a\" 1) "
                + "(hashtable-update! h \"a\" (lambda (v) (+ v 1)) 0) (hashtable-ref h \"a\" #f)"));
        assertEquals("0.5", val("(/ 1 2)"));
        assertEquals("2", val("(/ 4 2)"));
        assertEquals("1.5", val("(exact->inexact 3/2)"));
        assertEquals("(\"a\\nb\")", val("(list (list->string (list #\\a #\\newline #\\b)))"));
        assertEquals("(#\\a)", val("(list (string-ref \"abc\" 0))"));
        assertEquals("(a . b)", val("(cons 'a 'b)"));
        assertEquals("#t", val("(equal? '(1 #(2 \"x\")) (list 1 (vector 2 \"x\")))"));
        assertEquals("(\"[1,{\\\"a\\\":null}]\")", val("(list (json-stringify (vector 1 '((\"a\" . null)))))"));
        assertEquals("{}", val("(json-stringify '())"));
        assertEquals("#t", val("(string-prefix? \"ab\" \"abc\")"));
        assertEquals("#t", val("(string-contains? \"hello\" \"ell\")"));
        assertEquals("x", val("(string-trim \"  x \")"));
        assertEquals("d", val("(ref '((\"k\" . 7)) \"nope\" \"d\")"));
        assertEquals("1000000", val("(let loop ((i 0)) (if (= i 1000000) i (loop (+ i 1))))"), "尾调用不长栈");
        assertEquals("50000", val("(define (count n) (if (= n 0) 0 (+ 1 (count (- n 1))))) (count 50000)"), "深递归");
    }

    @Test
    void errorsReadLikeChez() {
        assertTrue(err("(car 1)").contains("Exception in car: 1 is not a pair"));
        assertTrue(err("(error 'call \"boom\")").contains("Exception in call: boom"));
        assertTrue(err("(error 'f \"bad\" 1 2)").contains("bad with irritants (1 2)"));
        assertTrue(err("(raise 'oops)").contains("non-condition value oops"));
        assertTrue(err("(1 2)").contains("attempt to apply non-procedure 1"));
        assertTrue(err("((lambda (x) x))").contains("incorrect number of arguments"));
        assertEquals("caught", val("(guard (e [(string? e) e] [else \"caught\"]) (car '()))"));
        assertEquals("boom", val("(guard (e [(error? e) (condition-message e)]) (error 'x \"boom\"))"));
        assertEquals("42", val("(with-exception-handler (lambda (e) 42) (lambda () (raise-continuable 'c)))"));
        assertTrue(err("(guard (e [(string? e) e]) (raise 'not-a-string))").contains("not-a-string"), "没中的条款原样再抛");
    }

    // ── 围栏 ─────────────────────────────────────────────────────────────

    @Test
    void dangerousNamesAreNotBound() {
        for (String code : List.of("(open-input-file \"/etc/passwd\")", "(system \"ls\")", "(load \"x.ss\")",
                "(eval '(+ 1 2))", "(foreign-procedure \"getpid\" () int)", "(datum->syntax #'x 'y)",
                "(call-with-output-file \"/tmp/x\" (lambda (p) 1))", "(display 1)", "(write 1)", "(getenv \"HOME\")",
                "(exit)", "(sleep 1)")) {
            String e = err(code);
            assertTrue(e.contains("not bound") || e.contains("invalid syntax"), code + " → " + e);
        }
    }

    @Test
    void readerFence() {
        CodeModeResult r = run("(#%system \"ls\")");
        assertFalse(r.ok());
        assertTrue(r.error().contains("#%"));
        assertTrue(CodeMode.forbidden("(#3%car '(1))"));
        assertFalse(CodeMode.forbidden("#t #\\a #(1 2) #x10"));
        assertTrue(err("(+ 1").contains("读不了"));
        assertFalse(run("   ").ok());
        assertFalse(run("; 只有注释\n#| 块 |#").ok());
        assertEquals("16", val("#x10"));
        assertEquals("2", val("#;(ignored) 2"));
    }

    // ── 限额 ─────────────────────────────────────────────────────────────

    @Test
    void limits() {
        CodeModeResult t = CodeMode.run("(let loop () (loop))", null, CodeMode.Limits.DEFAULT.withTimeoutMs(300), null);
        assertFalse(t.ok());
        assertTrue(t.error().contains("超时"), t.error());

        CodeModeResult c = CodeMode.run("(let loop ([i 0]) (guard (e [#t 'swallowed]) (call \"t\" '())) (loop (+ i 1)))",
                (n, a, k) -> JsonNodeFactory.instance.numberNode(1), CodeMode.Limits.DEFAULT.withMaxCalls(3), null);
        assertFalse(c.ok());
        assertTrue(c.error().contains("3 次"), c.error());
        assertEquals(List.of("t", "t", "t"), c.calls(), "已经调过的记着");

        CodeModeResult o = CodeMode.run("(make-string 100 #\\x)", null, CodeMode.Limits.DEFAULT.withMaxOutput(10), null);
        assertTrue(o.ok());
        assertTrue(o.value().contains("截断"));

        CodeModeResult s = CodeMode.run("(let loop ((i 0)) (loop (+ i 1)))", null, CodeMode.Limits.DEFAULT.withMaxSteps(10_000), null);
        assertFalse(s.ok());
        assertTrue(s.error().contains("步数"), s.error());

        CodeModeResult m = run("(let loop ((l '())) (loop (cons 1 l)))");
        assertFalse(m.ok());
        assertTrue(m.error().contains("内存") || m.error().contains("步数"), m.error());

        assertTrue(err("(make-string 100000000)").contains("太长"));
    }

    @Test
    void cancelPropagates() {
        CancelToken tok = new CancelToken();
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            tok.cancel();
        });
        assertThrows(CancelToken.CancelledException.class,
                () -> CodeMode.run("(let loop () (loop))", null, CodeMode.Limits.DEFAULT, tok));
    }

    // ── call ─────────────────────────────────────────────────────────────

    @Test
    void callShapesAndErrors() {
        AtomicReference<JsonNode> seen = new AtomicReference<>();
        CodeModeResult r = CodeMode.run("(let ([v (call \"srv.tool\" '((\"q\" . 1) (\"xs\" . #(1 \"a\"))))]) (ref v \"n\"))",
                (name, args, k) -> {
                    seen.set(args);
                    return JSON.readTree("{\"n\": 42}");
                });
        assertTrue(r.ok(), r.error());
        assertEquals("42", r.value());
        assertEquals(List.of("srv.tool"), r.calls());
        assertEquals("{\"q\":1,\"xs\":[1,\"a\"]}", seen.get().toString());

        CodeMode.Caller boom = (n, a, k) -> { throw new ToolException("boom"); };
        assertEquals("caught", CodeMode.run("(guard (e [#t \"caught\"]) (call \"bad\" '()))", boom).value());
        CodeModeResult u = CodeMode.run("(call \"bad\" '())", boom);
        assertFalse(u.ok());
        assertTrue(u.error().contains("boom"));
        assertEquals("pong", CodeMode.run("(call \"x\")", (n, a, k) -> JsonNodeFactory.instance.stringNode("pong")).value());
    }

    // ── execute 工具 ─────────────────────────────────────────────────────

    static ObjectNode schema(String json) { return (ObjectNode) JSON.readTree(json); }

    static final AgentTool T_RO = AgentTool.builder("srv_peek").description("看一眼")
            .rawSchema(schema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"))
            .readOnly().handler((env, args) -> "{\"got\": \"" + args.str("path") + "\"}").build();
    static final AgentTool T_W = AgentTool.builder("srv_poke").description("会写的\n第二行")
            .rawSchema(schema("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}}}"))
            .sensitive().handler((env, args) -> "done").build();
    static final AgentTool T_PONG = AgentTool.builder("srv_ping").description("ping")
            .rawSchema(schema("{\"type\":\"object\",\"properties\":{}}"))
            .readOnly().handler((env, args) -> "pong").build();

    static ToolOutput exec(AgentTool x, String code) {
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        in.put("code", code);
        return x.execute(ToolEnv.of(new Workspace(Path.of("."))), in);
    }

    @Test
    void executeTool() {
        assertNull(ExecuteTool.create(List.of()), "没有 MCP 工具就不造");
        String cat = ExecuteTool.catalog(List.of(T_RO, T_W));
        assertTrue(cat.contains("srv.peek  {path: string}  看一眼"), cat);
        assertTrue(cat.contains("srv.poke  {n?: integer}  会写的 第二行"), cat);
        assertEquals("srv_peek", ExecuteTool.resolveName(List.of(T_RO), "srv.peek").name());
        assertEquals("srv_peek", ExecuteTool.resolveName(List.of(T_RO), "srv_peek").name());
        assertNull(ExecuteTool.resolveName(List.of(T_RO), "srv.nope"));

        AgentTool x = ExecuteTool.create(List.of(T_RO));
        assertFalse(x.sensitive(), "只读目录 → 不 sensitive");
        assertFalse(x.readOnly(), "它能调目录里任何一件，不算只读");
        assertTrue(x.description().contains("srv.peek"));
        ToolOutput ok = exec(x, "(define r (call \"srv.peek\" '((\"path\" . \"a.txt\")))) (log! \"r=~s\" r) (ref r \"got\")");
        assertFalse(ok.error(), ok.text());
        assertTrue(ok.text().contains("a.txt") && ok.text().contains("--- log ---") && ok.text().contains("调了 1 次：srv.peek"), ok.text());
        ToolOutput nope = exec(x, "(call \"srv.nope\" '())");
        assertTrue(nope.error() && nope.text().contains("没有叫"), nope.text());
        assertTrue(exec(x, "").error());
        assertTrue(ExecuteTool.create(List.of(T_RO, T_W)).sensitive(), "有会写的 → sensitive");

        // 工具回的不是 JSON（"pong"）→ 原串
        ToolOutput pong = exec(ExecuteTool.create(List.of(T_PONG)), "(string-append \"got: \" (call \"srv.ping\" '()))");
        assertFalse(pong.error(), pong.text());
        assertTrue(pong.text().contains("got: pong"));
        // 没有返回值
        assertTrue(exec(x, "(if #f #f)").text().startsWith("（没有返回值）"));
        // 子工具失败 → 脚本里 guard 接得住
        AgentTool failing = AgentTool.builder("srv_fail").description("坏").readOnly()
                .handler((env, args) -> { throw new ToolException("坏了"); }).build();
        ToolOutput g = exec(ExecuteTool.create(List.of(failing)),
                "(guard (e [(error? e) (condition-message e)]) (call \"srv.fail\" '()))");
        assertTrue(g.text().contains("srv.fail 失败：坏了"), g.text());
    }
}
