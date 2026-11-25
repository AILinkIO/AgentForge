package io.ailink.agentforge.sandbox.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;


import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Windows 回环 TCP 连接的 owner PID 与令牌受限 SID（A-46c；照 Codex {@code network-proxy/src/windows_tcp_attribution.rs}）：
 * 只读 MIB_TCPTABLE_OWNER_PID 与 TokenRestrictedSids 两个结构体；四元组匹配与判定在 Java 上层（io.ailink.agentforge.sandbox.windows）。查询失败一律回 0 / {@code null}（fail closed）。⚠ 未验证。
 */
public final class WinTcp {
    private WinTcp() {
    }

    private static final int OWNER_PID_ROW_SIZE = 24;

    /**
     * 按 IPv4 四元组（地址 4 字节内存序、端口主机序）在 owner PID 表里找 source = local、destination = remote 的行；找不到 ⇒ 0。
     */
    public static int owningProcessId(byte[] sourceIp, int sourcePort, byte[] destinationIp, int destinationPort) {
        // 表内容会在连接建立 / 关闭时变化；给三次缓冲重试，仍拿不到快照就拒绝
        try (Arena a = Arena.ofConfined()) {
            MemorySegment size = a.allocate(JAVA_INT);
            for (int attempt = 0; attempt < 3; attempt++) {
                size.set(JAVA_INT, 0, 0);
                int result = WinApi.callInt(WinApi.H.GET_EXTENDED_TCP_TABLE, true, MemorySegment.NULL, size, 1, WinApi.AF_INET,
                        WinApi.TCP_TABLE_OWNER_PID_CONNECTIONS, 0);
                if (result != WinApi.ERROR_INSUFFICIENT_BUFFER || size.get(JAVA_INT, 0) == 0) return 0;
                int pid = readOwnerPid(size.get(JAVA_INT, 0) + 4096, sourceIp, sourcePort, destinationIp, destinationPort);
                if (pid != 0) return pid;
            }
        }
        return 0;
    }

    private static int readOwnerPid(int size, byte[] sIp, int sPort, byte[] dIp, int dPort) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment table = a.allocate(size, 8);
            MemorySegment sz = a.allocate(JAVA_INT);
            sz.set(JAVA_INT, 0, size);
            if (WinApi.callInt(WinApi.H.GET_EXTENDED_TCP_TABLE, true, table, sz, 1, WinApi.AF_INET, WinApi.TCP_TABLE_OWNER_PID_CONNECTIONS, 0) != 0) return 0;
            long rowCount = Integer.toUnsignedLong(table.get(JAVA_INT, 0));
            if (rowCount > (Integer.toUnsignedLong(sz.get(JAVA_INT, 0)) - 4) / OWNER_PID_ROW_SIZE) return 0;
            for (int i = 0; i < rowCount; i++) {
                long row = 4 + (long) i * OWNER_PID_ROW_SIZE;
                if (!endpoint(table, row + 4, row + 8, sIp, sPort) || !endpoint(table, row + 12, row + 16, dIp, dPort)) continue;
                return Math.max(table.get(JAVA_INT, row + 20), 0);
            }
            return 0;
        }
    }

    // MIB_TCPROW 的端口是网络字节序（低 16 位），IPv4 地址是内存序的 4 字节
    private static boolean endpoint(MemorySegment t, long addrAt, long portAt, byte[] ip, int port) {
        byte[] got = new byte[4];
        MemorySegment.copy(t, JAVA_BYTE, addrAt, got, 0, 4);
        int p = Short.toUnsignedInt(Short.reverseBytes(t.get(JAVA_SHORT, portAt)));
        return Arrays.equals(got, ip) && p == port;
    }

    /** 进程主令牌的受限 SID；失败 ⇒ {@code null}；非受限令牌通常是空表。 */
    public static List<String> restrictedSids(int processId) {
        if (processId <= 0) return null;
        MemorySegment process = WinApi.callPtr(WinApi.H.OPEN_PROCESS, WinApi.PROCESS_QUERY_LIMITED_INFORMATION, 0, processId);
        if (WinApi.isNull(process)) return null;
        try {
            MemorySegment token = WinApi.openProcessToken(process, WinApi.TOKEN_QUERY);
            if (token == null) return null;
            try {
                return readRestrictedSids(token);
            } finally {
                WinApi.closeHandle(token);
            }
        } finally {
            WinApi.closeHandle(process);
        }
    }

    private static List<String> readRestrictedSids(MemorySegment token) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment needed = a.allocate(JAVA_INT);
            if (WinApi.callBool(WinApi.H.GET_TOKEN_INFORMATION, token, WinApi.TOKEN_RESTRICTED_SIDS, MemorySegment.NULL, 0, needed)) return null;
            int n = needed.get(JAVA_INT, 0);
            if (WinApi.lastError() != WinApi.ERROR_INSUFFICIENT_BUFFER || n == 0) return null;
            MemorySegment buffer = a.allocate(n, 8);
            if (!WinApi.callBool(WinApi.H.GET_TOKEN_INFORMATION, token, WinApi.TOKEN_RESTRICTED_SIDS, buffer, n, needed)) return null;
            long count = Integer.toUnsignedLong(buffer.get(JAVA_INT, 0));
            long ptr = ADDRESS.byteSize();
            // 照 C# 版：条目步长按指针大小算
            long entrySize = ptr;
            if (n < ptr || count > (n - ptr) / entrySize) return null;
            var result = new ArrayList<String>();
            for (long i = 0; i < count; i++) {
                MemorySegment sid = buffer.get(ADDRESS, ptr + i * entrySize);
                if (WinApi.isNull(sid)) return null;
                result.add(WinAcl.nativeSidToString(sid));
            }
            return result;
        }
    }
}
