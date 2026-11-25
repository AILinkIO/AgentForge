package io.ailink.agentforge.sandbox.linux;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.exec.Exec;
import io.ailink.agentforge.sandbox.exec.ExecBackend;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.exec.ExecResult;
import io.ailink.agentforge.sandbox.policy.AccessMode;
import io.ailink.agentforge.sandbox.policy.NetworkMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PolicyEntry;
import io.ailink.agentforge.sandbox.policy.PolicyPath;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;
import io.ailink.agentforge.sandbox.policy.SpecialKind;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 本机真跑 Landlock + seccomp。不是 Linux / 原生件缺失 / 内核不支持 ⇒ 跳过（原生件由宿主提供，本模块不带）。 */
class LandlockSandboxTest {
    private static final LandlockSandbox SHARED = new LandlockSandbox();

    static LandlockSandbox requireLandlock() {
        assumeTrue(linux(), "Landlock 只在 Linux 上");
        SandboxCapabilities caps = SHARED.probe();
        assumeTrue(caps.available(), () -> "本机 Landlock 不可用：" + caps.unavailableReason());
        return SHARED;
    }

    private static SandboxPolicy workspace(NetworkMode network) {
        return SandboxPolicy.workspaceWrite(List.of(), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP).withNetwork(network);
    }

    private static ExecBackend wrap(LandlockSandbox s, SandboxPolicy pol, String cwd) {
        return s.wrap(Exec.localBackend(), pol, PathContext.of(cwd));
    }

    @Test
    void probeReportsUnavailableWithCodeWhenNativeIsMissing() {
        assumeTrue(linux());
        SandboxCapabilities caps = SHARED.probe();
        if (LandlockSandbox.nativePath() == null) {
            assertFalse(caps.available());
            assertEquals("sandbox/landlock-native-missing", caps.unavailableReason().code());
        }
    }

    @Test
    void probeReportsLandlockAndSeccomp() {
        LandlockSandbox s = requireLandlock();
        SandboxCapabilities caps = s.probe();
        assertEquals(LandlockSandbox.NAME, caps.name());
        assertTrue(caps.usesSeccomp());
        assertTrue(exists(LandlockSandbox.nativePath()));
    }

    @Test
    void writableRootWorksButOutsideStaysReadOnly() throws Exception {
        LandlockSandbox s = requireLandlock();
        withTempDirs(2, d -> {
            String w = d[0], outside = d[1];
            ExecBackend b = wrap(s, workspace(NetworkMode.RESTRICTED), w);
            ExecResult inside = sh(b, w, "echo ok > inside.txt && cat inside.txt");
            ExecResult out = sh(b, w, "echo no > '" + join(outside, "escape.txt") + "'");
            assertEquals(0, inside.exitCode(), inside.stderr());
            assertEquals("ok", inside.stdout().strip());
            assertNotEquals(0, out.exitCode());
            assertFalse(exists(join(outside, "escape.txt")));
        });
    }

    @Test
    void restrictedNetworkBlocksTcpButUnrestrictedNetworkIsOpen() throws Exception {
        LandlockSandbox s = requireLandlock();
        withTempDirs(1, d -> {
            String w = d[0];
            ExecResult tcp = Exec.run(wrap(s, workspace(NetworkMode.RESTRICTED), w),
                    ExecRequest.of(List.of("/usr/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/1"), w));
            ExecResult host = Exec.run(wrap(s, workspace(NetworkMode.ENABLED), w),
                    ExecRequest.of(List.of("/usr/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/1"), w));
            assertNotEquals(0, tcp.exitCode());
            assertFalse(tcp.stderr().contains("Connection refused"), tcp.stderr());
            assertNotEquals(0, host.exitCode());
            assertTrue(host.stderr().contains("Connection refused"), host.stderr());
        });
    }

    @Test
    void blobUsesLittleEndianAndCollectsFileRules() {
        String workspace = linux() ? "/workspace" : "C:\\workspace";
        SandboxPolicy pol = SandboxPolicy.restricted(List.of(PolicyEntry.of(PolicyPath.special(SpecialKind.ROOT), AccessMode.READ),
                PolicyEntry.of(PolicyPath.special(SpecialKind.PROJECT_ROOTS), AccessMode.WRITE))).withProtectedNames(List.of());
        LandlockSandbox.Blob blob = LandlockSandbox.buildBlob(pol, PathContext.of(workspace));
        ByteBuffer b = ByteBuffer.wrap(blob.blob()).order(ByteOrder.LITTLE_ENDIAN);
        assertTrue(blob.fsEnabled());
        assertTrue(blob.netRestricted());
        assertEquals(LandlockSandbox.MAGIC, b.getInt(0));
        assertEquals(LandlockSandbox.VERSION, b.getInt(4));
        assertEquals(LandlockSandbox.NET_RESTRICTED, b.getInt(8));
        assertEquals(1, b.getInt(12));
        assertEquals(2, b.getInt(16));
        assertEquals(LandlockSandbox.READ_ONLY_ACCESS, b.getLong(20));
        assertEquals('/', blob.blob()[32]);
    }

    @Test
    void rewriteLeavesUnrestrictedPoliciesAlone() {
        ExecRequest req = ExecRequest.of("/bin/true");
        assertSame(req, LandlockSandbox.rewrite(SandboxPolicy.dangerFullAccess(), PathContext.of("/"), req));
    }
}
