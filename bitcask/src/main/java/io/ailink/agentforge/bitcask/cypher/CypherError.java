package io.ailink.agentforge.bitcask.cypher;

import java.util.List;

/**
 * 引擎内部的 Cypher 错误（解析 / 语义 / 规划 / 执行）。对外由 {@link Cypher} 翻成 {@link CypherException}。
 * type / detail 是 openCypher TCK 的错误类型与细节代码，在抛错处给出（解析错误没给时归 SyntaxError / UnexpectedSyntax）。
 * <p>对照 clj：{@code parser/fail} ⇒ {@link #fail}；{@code x/fail} ⇒ {@link #exec(Object...)}；{@code x/failc} ⇒ {@link #execc}。
 */
final class CypherError extends RuntimeException {
    final String stage;   // parse | semantic | plan | exec
    final Long line, column;
    final String type, detail;

    CypherError(String stage, String message, Long line, Long column, String type, String detail) {
        super(message, null, false, false);
        this.stage = stage;
        this.line = line;
        this.column = column;
        this.type = type;
        this.detail = detail;
    }

    /**
     * clj {@code (parser/fail stage msg loc [ty de])}：stage ∈ parse / semantic / plan；loc = [行 列]（Long 列表）或 null；
     * code = [类型 细节]（可为 null，元素也可为 null）。总是抛，返回类型只为写 {@code throw fail(...)}。
     */
    static CypherError fail(String stage, Object msg, Object loc, List<?> code) {
        Long line = null, col = null;
        if (loc instanceof List<?> l && l.size() >= 2) {
            line = ((Number) l.get(0)).longValue();
            col = ((Number) l.get(1)).longValue();
        }
        String ty = code == null ? null : (String) code.get(0);
        String de = code == null || code.size() < 2 ? null : (String) code.get(1);
        throw new CypherError(stage, String.valueOf(msg), line, col, ty, de);
    }

    /** clj {@code (parser/fail msg loc)}：解析阶段、不带代码。 */
    static CypherError fail(Object msg, Object loc) {
        throw fail("parse", msg, loc, null);
    }

    /** clj {@code (x/fail & parts)}：执行期错误（不归 openCypher 的类）；parts 按 clj str 拼接（null ⇒ ""）。 */
    static CypherError exec(Object... parts) {
        throw new CypherError("exec", Clj.str(parts), null, null, null, null);
    }

    /** clj {@code (x/failc [ty de] & parts)}：执行期错误，带 openCypher 的错误类型与细节代码（细节可以是 null）。 */
    static CypherError execc(String type, String detail, Object... parts) {
        throw new CypherError("exec", Clj.str(parts), null, null, type, detail);
    }

    boolean isParseError() {
        return !"exec".equals(stage);
    }
}
