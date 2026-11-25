package io.ailink.agentforge.bitcask.ffm;

import static io.ailink.agentforge.bitcask.ffm.NativeTypes.FAULT_CODE;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.FAULT_DETAIL;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.FAULT_ERRNUM;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import io.ailink.agentforge.bitcask.BitcaskException;

/** C 侧 fault（{@code bitcask_fault_t}）⇒ {@link BitcaskException}：原始错误码 + errno + 详情 + C 入口名。 */
public final class Faults {
    public static final int IO = 1;
    public static final int NOT_FOUND = 3;
    public static final int INVALID_OPTION = 9;
    public static final int MODE_MISMATCH = 11;
    public static final int ANALYZER_MISMATCH = 12;
    public static final int INDEX_CORRUPT = 16;
    /** libbitcask 8.0.0：原子批前置条件不成立（整批零副作用）。 */
    public static final int CONDITION_FAILED = 17;

    private Faults() {
    }

    /** 从 fault 读 errno 与详情。 */
    public static BitcaskException from(int error, MemorySegment fault, String operation) {
        String detail = null;
        int errno = 0;
        if (fault != null && !fault.equals(MemorySegment.NULL)) {
            errno = fault.get(JAVA_INT, FAULT_ERRNUM);
            if (fault.get(JAVA_BYTE, FAULT_DETAIL) != 0) detail = fault.getString(FAULT_DETAIL, StandardCharsets.UTF_8);
        }
        return BitcaskException.fault(error, errno, detail, operation);
    }

    /** 非 0 ⇒ 抛。 */
    public static void check(int error, MemorySegment fault, String operation) {
        if (error != 0) throw from(error, fault, operation);
    }

    /** 迭代失败：fault 码为 OK 时按 IO 报。 */
    public static BitcaskException iterError(MemorySegment fault, String operation) {
        int code = fault.get(JAVA_INT, FAULT_CODE);
        return from(code == 0 ? IO : code, fault, operation);
    }
}
