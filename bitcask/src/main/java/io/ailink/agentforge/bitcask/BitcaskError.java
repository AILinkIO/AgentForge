package io.ailink.agentforge.bitcask;

/**
 * libbitcask C 侧错误码（{@code bitcask_error_t}；序数 = 数值）。{@link #code()} 是对应的稳定错误码（{@code bitcask/io} …），
 * 调用方按它或按本枚举分支，⛔ 不比对消息（消息随界面语言变）。
 */
public enum BitcaskError {
    OK("ok"), IO("io"), BAD_CRC("bad-crc"), NOT_FOUND("not-found"), KEY_TOO_LARGE("key-too-large"),
    VALUE_TOO_LARGE("value-too-large"), ALREADY_EXISTS("already-exists"), READ_ONLY("read-only"), WRITE_LOCKED("write-locked"),
    INVALID_OPTION("invalid-option"), NO_INDEX("no-index"), MODE_MISMATCH("mode-mismatch"), ANALYZER_MISMATCH("analyzer-mismatch"),
    CLOSED("closed"), INDEX_REBUILD_FAILED("index-rebuild-failed"), BUSY("busy"), INDEX_CORRUPT("index-corrupt"),
    /** libbitcask 8.0.0：原子批前置条件不成立（整批零副作用）。 */
    CONDITION_FAILED("condition-failed");

    private final String keyword;

    BitcaskError(String keyword) {
        this.keyword = keyword;
    }

    /** 短名（{@code read-only}；同 clj-bitcask 的 {@code :bitcask/error} 关键字）。 */
    public String keyword() {
        return keyword;
    }

    /** 稳定错误码（{@code bitcask/read-only}）；OK ⇒ {@code bitcask/fault}（同 clj-bitcask）。 */
    public String code() {
        return this == OK ? "bitcask/fault" : "bitcask/" + keyword;
    }

    /** C 侧数值。 */
    public int value() {
        return ordinal();
    }

    /** 数值 ⇒ 枚举；不认识 ⇒ null。 */
    public static BitcaskError of(int raw) {
        BitcaskError[] all = values();
        return raw >= 0 && raw < all.length ? all[raw] : null;
    }

    /** 数值 ⇒ 稳定码；不认识（或 0）⇒ {@code bitcask/fault}。 */
    public static String codeOf(int raw) {
        BitcaskError e = of(raw);
        return e == null ? "bitcask/fault" : e.code();
    }
}
