package io.ailink.agentforge.sandbox.windows;

/**
 * ACL 账本的一条改动：{@code path|sid|kind|mask|inheritance[|sddl]}；kind：grant / read / deny / deny-read / synthetic。
 * 崩溃后宿主按账撤销（{@link WindowsSupport#recoverLedger}）。
 *
 * @param sddl deny-read 的 DACL 快照（撤销时按它还原；REVOKE 会误伤用户原有 ACE）；可空
 */
public record LedgerEntry(String path, String sid, String kind, int mask, int inheritance, String sddl) {
    public LedgerEntry(String path, String sid, String kind, int mask, int inheritance) {
        this(path, sid, kind, mask, inheritance, null);
    }

    /** 同一处 ACE 的身份（并发运行按它做份数引用计数；快照不进键）。 */
    public String key() {
        return WindowsSupport.escape(path) + "|" + sid + "|" + kind + "|" + Integer.toUnsignedString(mask) + "|" + inheritance;
    }

    String format() {
        return key() + (sddl != null ? "|" + WindowsSupport.escape(sddl) : "");
    }

    public LedgerEntry withSddl(String value) {
        return new LedgerEntry(path, sid, kind, mask, inheritance, value);
    }
}
