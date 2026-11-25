package io.ailink.agentforge.sandbox.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import io.ailink.agentforge.sandbox.SandboxException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;


/**
 * ACL 的原生改动（参照 Codex {@code windows-sandbox-rs/src/acl.rs}：SET_ACCESS + 双继承标志、刻意不给 FILE_DELETE_CHILD 的 deny、REVOKE_ACCESS 撤销）。
 * 账本、引用计数与撤销次序在 {@code io.ailink.agentforge.sandbox.windows.WindowsSupport}。⚠ 未验证。失败 ⇒ {@code sandbox/win-acl-failed}（路径, 错误码）。
 */
public final class WinAcl {
    private WinAcl() {
    }

    /** {@link #setAce} 的结果：DACL 被改写。 */
    public static final int CHANGED = 0;
    /** 已经有覆盖同等权限的同类 ACE ⇒ 不重复写（省掉继承传播的代价）。 */
    public static final int ALREADY_PRESENT = 1;
    /** 该主体已有 deny ⇒ 不授 allow（deny 优先）。 */
    public static final int BLOCKED = 2;

    private static final byte ACCESS_ALLOWED_ACE_TYPE = 0x00;
    private static final byte ACCESS_DENIED_ACE_TYPE = 0x01;
    private static final int ACL_SIZE_INFORMATION = 2;

    private static SandboxException aclFailed(String path, int error) {
        return SandboxException.ofNative("sandbox/win-acl-failed", path, error);
    }

    /** 字符串 SID ⇒ 原生 SID（调用方 {@link WinApi#localFree}）。 */
    static MemorySegment stringSidToNative(String sid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            if (!WinApi.callBool(WinApi.H.CONVERT_STRING_SID_TO_SID, WinApi.wide(arena, sid), out)) throw aclFailed(sid, WinApi.lastError());
            return out.get(ADDRESS, 0);
        }
    }

    /** 原生 SID ⇒ 字符串。 */
    static String nativeSidToString(MemorySegment sid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            if (!WinApi.callBool(WinApi.H.CONVERT_SID_TO_STRING_SID, sid, out)) throw aclFailed("SID", WinApi.lastError());
            MemorySegment text = out.get(ADDRESS, 0);
            try {
                return WinApi.readWide(text);
            } finally {
                WinApi.localFree(text);
            }
        }
    }

    private record AceInfo(byte type, byte flags, int mask, String sid) {
    }

    /** 读路径的 DACL 指针（⚠ 故意不 free：真机实测对它 LocalFree 会堆损坏，见 C# 版注释）。 */
    private static MemorySegment namedDacl(Arena arena, String extended) {
        MemorySegment dacl = arena.allocate(ADDRESS);
        MemorySegment sd = arena.allocate(ADDRESS);
        int err = WinApi.callInt(WinApi.H.GET_NAMED_SECURITY_INFO, true, WinApi.wide(arena, extended), WinApi.SE_FILE_OBJECT,
                WinApi.DACL_SECURITY_INFORMATION, MemorySegment.NULL, MemorySegment.NULL, dacl, MemorySegment.NULL, sd);
        if (err != 0) throw aclFailed(extended, err);
        return dacl.get(ADDRESS, 0);
    }

    /**
     * 给 {@code path} 加 / 撤一条 SID 的 ACE（{@code mode}：{@link WinApi#SET_ACCESS} / {@link WinApi#DENY_ACCESS} / {@link WinApi#REVOKE_ACCESS}）。
     * 命名式 API + {@code \\?\} 前缀（长路径）。先读现有 ACE：已有等价 ACE 就不重写，该主体已有 deny 就不授 allow。
     * 回 {@link #CHANGED} / {@link #ALREADY_PRESENT} / {@link #BLOCKED}。
     */
    public static int setAce(String path, String sid, int mode, int mask, int inheritance) {
        String extended = "\\\\?\\" + path;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dacl = namedDacl(arena, extended);
            List<AceInfo> mine = readAces(dacl).stream().filter(a -> a.sid().equalsIgnoreCase(sid)).toList();
            if (mode == WinApi.SET_ACCESS) {
                if (mine.stream().anyMatch(a -> a.type() == ACCESS_DENIED_ACE_TYPE)) return BLOCKED;
                AceInfo allow = mine.stream().filter(a -> a.type() == ACCESS_ALLOWED_ACE_TYPE).findFirst().orElse(null);
                if (allow != null && covers(allow.mask(), mask) && coversFlags(allow.flags(), inheritance)) return ALREADY_PRESENT;
            } else if (mode == WinApi.DENY_ACCESS) {
                AceInfo deny = mine.stream().filter(a -> a.type() == ACCESS_DENIED_ACE_TYPE).findFirst().orElse(null);
                if (deny != null && covers(deny.mask(), mask) && coversFlags(deny.flags(), inheritance)) return ALREADY_PRESENT;
            }
            MemorySegment sidPtr = stringSidToNative(sid);
            try {
                // EXPLICIT_ACCESS_W：grfAccessPermissions@0 grfAccessMode@4 grfInheritance@8 TRUSTEE@16（TrusteeForm@12 ptstrName@24）
                MemorySegment ea = arena.allocate(WinApi.EXPLICIT_ACCESS_SIZE, 8);
                ea.set(JAVA_INT, 0, mask);
                ea.set(JAVA_INT, 4, mode);
                ea.set(JAVA_INT, 8, inheritance);
                ea.set(JAVA_INT, 16 + 12, WinApi.TRUSTEE_IS_SID);
                ea.set(ADDRESS, 16 + 24, sidPtr);
                MemorySegment newAcl = arena.allocate(ADDRESS);
                int aclErr = WinApi.callInt(WinApi.H.SET_ENTRIES_IN_ACL, true, 1, ea, dacl, newAcl);
                if (aclErr != 0) throw aclFailed(path, aclErr);
                MemorySegment acl = newAcl.get(ADDRESS, 0);
                try {
                    // 带继承标志时加 SE_DACL_AUTO_INHERIT_REQ：把这条 ACE 传播给已存在的子对象
                    int securityInfo = WinApi.DACL_SECURITY_INFORMATION | (inheritance != 0 ? WinApi.SE_DACL_AUTO_INHERIT_REQ : 0);
                    int setErr = WinApi.callInt(WinApi.H.SET_NAMED_SECURITY_INFO, true, WinApi.wide(arena, extended), WinApi.SE_FILE_OBJECT, securityInfo,
                            MemorySegment.NULL, MemorySegment.NULL, acl, MemorySegment.NULL);
                    if (setErr != 0) throw aclFailed(path, setErr);
                } finally {
                    WinApi.localFree(acl);
                }
            } finally {
                WinApi.localFree(sidPtr);
            }
            return CHANGED;
        }
    }

    /** 读 DACL 里的 ACE（只认标准 allow / deny）。 */
    private static List<AceInfo> readAces(MemorySegment dacl) {
        var result = new ArrayList<AceInfo>();
        if (WinApi.isNull(dacl)) return result;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(12, 4);
            if (!WinApi.callBool(WinApi.H.GET_ACL_INFORMATION, dacl, info, 12, ACL_SIZE_INFORMATION)) return result;
            int count = info.get(JAVA_INT, 0);
            MemorySegment out = arena.allocate(ADDRESS);
            for (int i = 0; i < count; i++) {
                if (!WinApi.callBool(WinApi.H.GET_ACE, dacl, i, out)) continue;
                MemorySegment ace = out.get(ADDRESS, 0);
                if (WinApi.isNull(ace)) continue;
                ace = ace.reinterpret(Long.MAX_VALUE);
                byte type = ace.get(JAVA_BYTE, 0);
                byte flags = ace.get(JAVA_BYTE, 1);
                if (type != ACCESS_ALLOWED_ACE_TYPE && type != ACCESS_DENIED_ACE_TYPE) continue;
                int mask = ace.get(JAVA_INT, 4);
                // 标准 ACE 布局：头 4 字节 + 掩码 4 字节 + SID
                String sid;
                try {
                    sid = nativeSidToString(ace.asSlice(8));
                } catch (SandboxException e) {
                    continue;
                }
                if (!sid.isEmpty()) result.add(new AceInfo(type, flags, mask, sid));
            }
        }
        return result;
    }

    private static boolean covers(int existing, int required) {
        return (existing & required) == required;
    }

    private static boolean coversFlags(byte existing, int inheritance) {
        return inheritance == 0 || (existing & (byte) inheritance) == (byte) inheritance;
    }

    /** DACL 的字节快照（ACL 结构本身：头 + ACE 列表，可直接塞回 SetNamedSecurityInfoW），Base64；取不到 ⇒ {@code null}。 */
    public static String daclSnapshot(String path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dacl;
            try {
                dacl = namedDacl(arena, "\\\\?\\" + path);
            } catch (SandboxException e) {
                return null;
            }
            if (WinApi.isNull(dacl)) return null;
            dacl = dacl.reinterpret(Long.MAX_VALUE);
            // ACL 头：Revision(1) + Sbz1(1) + AclSize(2, 小端)
            int aclSize = Short.toUnsignedInt(dacl.get(JAVA_SHORT, 2));
            if (aclSize < 8) return null;
            byte[] bytes = new byte[aclSize];
            MemorySegment.copy(dacl, JAVA_BYTE, 0, bytes, 0, aclSize);
            return Base64.getEncoder().encodeToString(bytes);
        }
    }

    /** 按字节快照还原 DACL。 */
    public static void restoreDacl(String path, String snapshot) {
        byte[] bytes = Base64.getDecoder().decode(snapshot);
        if (bytes.length < 8) throw aclFailed(path, 0);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(bytes.length, 8);
            MemorySegment.copy(bytes, 0, buffer, JAVA_BYTE, 0, bytes.length);
            int err = WinApi.callInt(WinApi.H.SET_NAMED_SECURITY_INFO, true, WinApi.wide(arena, "\\\\?\\" + path), WinApi.SE_FILE_OBJECT,
                    WinApi.DACL_SECURITY_INFORMATION, MemorySegment.NULL, MemorySegment.NULL, buffer, MemorySegment.NULL);
            if (err != 0) throw aclFailed(path, err);
        }
    }
}
