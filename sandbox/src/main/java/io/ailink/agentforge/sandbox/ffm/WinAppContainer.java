package io.ailink.agentforge.sandbox.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.ailink.agentforge.sandbox.SandboxException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;


/**
 * AppContainer profile（CreateAppContainerProfile / DeriveAppContainerSidFromAppContainerName / DeleteAppContainerProfile）。⚠ 未验证。
 * 失败 ⇒ {@code sandbox/win-appcontainer-profile-failed} / {@code sandbox/win-appcontainer-profile-delete-failed}（HRESULT 的 0x 文本）。
 */
public final class WinAppContainer {
    private WinAppContainer() {
    }

    private static final int E_ALREADY_EXISTS = 0x800700B7;
    private static final int E_FILE_NOT_FOUND = 0x80070002;

    private static String formatHResult(int hr) {
        return String.format("0x%08X", hr);
    }

    /** 先 create，ERROR_ALREADY_EXISTS 才 derive；回 profile 的 SID 串。 */
    public static String ensureProfile(String name, List<String> capabilities) {
        var nativeSids = new ArrayList<MemorySegment>();
        try (Arena a = Arena.ofConfined()) {
            for (String c : capabilities) nativeSids.add(WinAcl.stringSidToNative(c));
            MemorySegment array = MemorySegment.NULL;
            if (!nativeSids.isEmpty()) {
                array = a.allocate(WinApi.SID_AND_ATTRIBUTES_SIZE * nativeSids.size(), 8);
                for (int i = 0; i < nativeSids.size(); i++) {
                    array.set(ADDRESS, i * WinApi.SID_AND_ATTRIBUTES_SIZE, nativeSids.get(i));
                    array.set(JAVA_INT, i * WinApi.SID_AND_ATTRIBUTES_SIZE + 8, WinApi.SE_GROUP_ENABLED);
                }
            }
            MemorySegment n = WinApi.wide(a, name);
            MemorySegment out = a.allocate(ADDRESS);
            int created = WinApi.callInt(WinApi.H.CREATE_APP_CONTAINER_PROFILE, false, n, n, n, array, nativeSids.size(), out);
            if (created != 0) {
                if (created != E_ALREADY_EXISTS) throw SandboxException.ofNative("sandbox/win-appcontainer-profile-failed", formatHResult(created));
                int existing = WinApi.callInt(WinApi.H.DERIVE_APP_CONTAINER_SID, false, n, out);
                if (existing != 0) throw SandboxException.ofNative("sandbox/win-appcontainer-profile-failed", formatHResult(existing));
            }
            MemorySegment sid = out.get(ADDRESS, 0);
            try {
                return WinAcl.nativeSidToString(sid);
            } finally {
                WinApi.localFree(sid);
            }
        } finally {
            for (MemorySegment s : nativeSids) WinApi.localFree(s);
        }
    }

    public static boolean profileExists(String name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment n = WinApi.wide(a, name);
            MemorySegment out = a.allocate(ADDRESS);
            int hr = WinApi.callInt(WinApi.H.CREATE_APP_CONTAINER_PROFILE, false, n, n, n, MemorySegment.NULL, 0, out);
            if (hr == 0) {
                WinApi.localFree(out.get(ADDRESS, 0));
                deleteProfile(name);
                return false;
            }
            return hr == E_ALREADY_EXISTS;
        }
    }

    public static void deleteProfile(String name) {
        try (Arena a = Arena.ofConfined()) {
            int hr = WinApi.callInt(WinApi.H.DELETE_APP_CONTAINER_PROFILE, false, WinApi.wide(a, name));
            if (hr != 0 && hr != E_FILE_NOT_FOUND) throw SandboxException.ofNative("sandbox/win-appcontainer-profile-delete-failed", formatHResult(hr));
        }
    }
}
