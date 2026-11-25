package io.ailink.agentforge.bitcask;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本库抛的错误：{@link #code()} = 稳定错误码（{@code bitcask/read-only}、{@code bitcask/disposed} …），调用方按码判别，
 * ⛔ 不比对消息（消息随界面语言变，见 {@link Messages}）。
 * <p>两类：
 * <ul>
 * <li>原生 fault（{@link #isNative()}）：另带 {@link #error()}（C 侧错误码）、{@link #rawError()}、{@link #errno()}、
 *     {@link #detail()}、{@link #operation()}（C 入口名）；消息格式同 clj-bitcask（{@code 入口：错误（errno n）：详情}）；</li>
 * <li>Java 侧拦下的（参数 / 选项 / 形状不合规）：只有码与参数 {@link #args()}。</li>
 * </ul>
 * 原子批前置条件不成立 ⇒ 码 {@code bitcask/condition-failed}，{@link #failedOp()} = 首个不成立的 op 下标（从 0 起）。
 * 其它附加数据（如向量配置不一致时库里与选项里的值）在 {@link #data()}。
 */
public class BitcaskException extends RuntimeException {
    private final String code;
    private final transient Object[] args;
    private final BitcaskError error;
    private final int rawError;
    private final int errno;
    private final String detail;
    private final String operation;
    private final long failedOp;
    private final transient Map<String, Object> data;

    private BitcaskException(String message, Throwable cause, String code, Object[] args, BitcaskError error, int rawError,
                             int errno, String detail, String operation, long failedOp, Map<String, Object> data) {
        super(message, cause);
        this.code = code;
        this.args = args;
        this.error = error;
        this.rawError = rawError;
        this.errno = errno;
        this.detail = detail;
        this.operation = operation;
        this.failedOp = failedOp;
        this.data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /** 子类（如 Cypher 的错误）用：码 + 已渲染的消息 + 文案参数 + 附加数据。 */
    protected BitcaskException(String code, String message, Object[] args, Map<String, Object> data) {
        this(message, null, code, args == null ? new Object[0] : args.clone(), null, -1, 0, null, null, -1, data);
    }

    /** Java 侧拦下的错：{@code of("bitcask/meta-duplicate-key", k)}（文案按当前语言渲染）。 */
    public static BitcaskException of(String code, Object... args) {
        return new BitcaskException(Messages.text(code, args), null, code, args.clone(), null, -1, 0, null, null, -1, null);
    }

    /** 同 {@link #of}，另带附加数据（图层把出问题的名字 / 值放进 {@code bitcask/arg}，Cypher 据此出稳定的英文报文）。 */
    public static BitcaskException ofData(String code, Map<String, Object> data, Object... args) {
        return new BitcaskException(Messages.text(code, args), null, code, args.clone(), null, -1, 0, null, null, -1, data);
    }

    /** 原生 fault（{@code raw} = C 侧错误码数值）。 */
    public static BitcaskException fault(int raw, int errno, String detail, String operation) {
        return new BitcaskException(faultText(operation, raw, errno, detail), null, BitcaskError.codeOf(raw), new Object[0],
                BitcaskError.of(raw), raw, errno, detail, operation, -1, null);
    }

    /**
     * Java 侧在交给 C 之前拦下、但按原生 fault 报的错（jieba 词典不齐、显式分词器与库不符）：
     * 码 = {@code messageCode}，详情 = 它的文案，{@link #error()} 照 {@code raw}。
     */
    public static BitcaskException fault(int raw, String operation, String messageCode, Object... args) {
        String detail = Messages.text(messageCode, args);
        return new BitcaskException(faultText(operation, raw, 0, detail), null, messageCode, args.clone(), BitcaskError.of(raw), raw,
                0, detail, operation, -1, null);
    }

    /** 原子批前置条件不成立（下标从 0 起），整批没有写。 */
    public static BitcaskException conditionFailed(long index) {
        int raw = BitcaskError.CONDITION_FAILED.value();
        return new BitcaskException(Messages.text("bitcask/batch-conflict", index), null, "bitcask/condition-failed", new Object[]{index},
                BitcaskError.CONDITION_FAILED, raw, 0, null, null, index, null);
    }

    /** 同一个错，消息末尾追加 {@code suffix}、并入附加数据（码与其它字段不变）。 */
    public BitcaskException withData(String suffix, Map<String, Object> extra) {
        Map<String, Object> d = new LinkedHashMap<>(data);
        d.putAll(extra);
        return new BitcaskException(getMessage() + suffix, this, code, args, error, rawError, errno, detail, operation, failedOp, d);
    }

    /** C 侧 fault 的报文（四种格式）。 */
    static String faultText(String operation, int raw, int errno, String detail) {
        String code = BitcaskError.codeOf(raw);
        String e = "bitcask/fault".equals(code) ? Messages.text("bitcask/fault", raw) : Messages.text(code);
        boolean hasErrno = errno != 0, hasDetail = detail != null && !detail.isEmpty();
        if (hasErrno && hasDetail) return Messages.text("bitcask/fault-message-errno-detail", operation, e, errno, detail);
        if (hasErrno) return Messages.text("bitcask/fault-message-errno", operation, e, errno);
        if (hasDetail) return Messages.text("bitcask/fault-message-detail", operation, e, detail);
        return Messages.text("bitcask/fault-message", operation, e);
    }

    /** 稳定错误码。 */
    public String code() {
        return code;
    }

    /** 文案参数（次序同资源里的 {0}、{1} …）。 */
    public List<Object> args() {
        return Arrays.asList(args.clone());
    }

    /** 是不是原生 fault（含按 fault 报的 Java 侧检查）。 */
    public boolean isNative() {
        return operation != null;
    }

    /** C 侧错误码（Java 侧拦下的 ⇒ null；不认识的数值 ⇒ null，看 {@link #rawError()}）。 */
    public BitcaskError error() {
        return error;
    }

    /** C 侧错误码数值（Java 侧拦下的 ⇒ -1）。 */
    public int rawError() {
        return rawError;
    }

    public int errno() {
        return errno;
    }

    /** C 侧 {@code fault.detail}（可能为 null）。 */
    public String detail() {
        return detail;
    }

    /** C 入口名（{@code bitcask_put} …）；Java 侧拦下的 ⇒ null。 */
    public String operation() {
        return operation;
    }

    /** 原子批冲突的 op 下标（从 0 起）；别的错 ⇒ -1。 */
    public long failedOp() {
        return failedOp;
    }

    /** 附加数据（只读）。 */
    public Map<String, Object> data() {
        return data;
    }
}
