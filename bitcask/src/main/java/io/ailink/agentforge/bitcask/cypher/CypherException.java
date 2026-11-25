package io.ailink.agentforge.bitcask.cypher;

import java.util.LinkedHashMap;
import java.util.Map;

import io.ailink.agentforge.bitcask.BitcaskException;
import io.ailink.agentforge.bitcask.Messages;

/**
 * Cypher 语句的错误：码恒为 {@code bitcask/cypher-error}；{@link #stage()} ∈ parse / semantic / plan / exec；
 * {@link #type()} / {@link #detail()} 是 openCypher 的错误类型与细节代码（如 SyntaxError / UndefinedVariable），在抛错处给出；
 * {@link #cypherMessage()} 是稳定的英文报文（{@link #getMessage()} 按界面语言渲染）。存储层的错误照常以 {@link BitcaskException} 抛出。
 */
public final class CypherException extends BitcaskException {
    private final String stage, cypherMessage, type, detail;
    private final Long line, column;

    CypherException(String stage, String message, Long line, Long column, String type, String detail) {
        super("bitcask/cypher-error", Messages.text("bitcask/cypher-error", stage, message), new Object[]{stage, message}, data(stage, message, line, column, type, detail));
        this.stage = stage;
        this.cypherMessage = message;
        this.line = line;
        this.column = column;
        this.type = type;
        this.detail = detail;
    }

    private static Map<String, Object> data(String stage, String message, Long line, Long column, String type, String detail) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("cypher/stage", stage);
        d.put("cypher/message", message);
        if (type != null) d.put("cypher/type", type);
        if (detail != null) d.put("cypher/detail", detail);
        if (line != null) d.put("cypher/line", line);
        if (column != null) d.put("cypher/column", column);
        return d;
    }

    static CypherException of(CypherError e) {
        String ty = e.type, de = e.detail;
        if (ty == null && "parse".equals(e.stage)) {
            ty = "SyntaxError";
            de = "UnexpectedSyntax";
        }
        CypherException x = new CypherException(e.stage, e.getMessage(), e.line, e.column, ty, de);
        return x;
    }

    /** parse / semantic / plan / exec。 */
    public String stage() {
        return stage;
    }

    /** 英文报文（不随界面语言变）。 */
    public String cypherMessage() {
        return cypherMessage;
    }

    /** 出错位置的行（从 1 起；没有 ⇒ null）。 */
    public Long line() {
        return line;
    }

    public Long column() {
        return column;
    }

    /** openCypher 错误类型（SyntaxError / SemanticError / TypeError / ArgumentError / EntityNotFound / ConstraintVerificationFailed …）。 */
    public String type() {
        return type;
    }

    /** openCypher 细节代码（可能为 null）。 */
    public String detail() {
        return detail;
    }
}
