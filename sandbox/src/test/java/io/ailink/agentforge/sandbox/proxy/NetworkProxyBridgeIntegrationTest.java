package io.ailink.agentforge.sandbox.proxy;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.exec.Exec;
import io.ailink.agentforge.sandbox.exec.ExecBackend;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.exec.ExecResult;
import io.ailink.agentforge.sandbox.linux.BwrapSandbox;
import io.ailink.agentforge.sandbox.linux.LandlockSandbox;
import io.ailink.agentforge.sandbox.policy.NetworkMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 本机真跑：bwrap 空 netns 内的目标进程经 SCM_RIGHTS 桥使用宿主代理。要 bwrap + curl + 宿主提供的原生件，缺一 ⇒ 跳过。 */
class NetworkProxyBridgeIntegrationTest {
    @Test
    void proxiedBwrapForwardsAllowedHttpAndBlocksDeniedAndDirectNet() throws Exception {
        assumeTrue(linux(), "bwrap 只在 Linux 上");
        assumeTrue(LandlockSandbox.nativePath() != null, "没有原生件 libagentforge_sandbox_native（代理桥要它）");
        assumeTrue(exists("/usr/bin/curl"), "没有 /usr/bin/curl");
        BwrapSandbox s = new BwrapSandbox(BwrapSandbox.Options.DEFAULT.withNetworkProxy(ProxyOptions.of(HostPolicy.allow("127.0.0.1")).withConnectTimeoutMs(2000)));
        SandboxCapabilities caps = s.probe();
        assumeTrue(caps.available(), () -> "本机 bwrap 不可用：" + caps.unavailableReason());
        try (ServerSocket up = NetworkProxyServerTest.listener()) {
            withTempDirs(1, d -> {
                String w = d[0];
                CompletableFuture<String> serve = NetworkProxyServerTest.serveHttp(up, "hello bridge");
                SandboxPolicy pol = SandboxPolicy.workspaceWrite(List.of(), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP).withNetwork(NetworkMode.PROXIED);
                ExecBackend backend = s.wrap(Exec.localBackend(), pol, PathContext.of(w));
                int port = up.getLocalPort();
                String script = ("set -eu\n"
                        + "test -n \"$HTTP_PROXY\"\n"
                        + "test -n \"$HTTPS_PROXY\"\n"
                        + "test \"$HTTP_PROXY\" = \"$HTTPS_PROXY\"\n"
                        + "test \"$HTTP_PROXY\" != \"$KS_PROXY_BRIDGE_SOCKET\"\n"
                        + "curl --noproxy '' -fsS --max-time 5 http://127.0.0.1:__PORT__/allowed\n"
                        + "denied_code=$(curl --noproxy '' -sS --max-time 5 -o /dev/null -w '%{http_code}' http://blocked.invalid/denied)\n"
                        + "test \"$denied_code\" = 403\n"
                        + "if env -u HTTP_PROXY -u http_proxy -u HTTPS_PROXY -u https_proxy -u ALL_PROXY -u all_proxy \\\n"
                        + "  curl -sS --noproxy '*' --max-time 2 http://127.0.0.1:__PORT__/direct >/dev/null 2>&1; then\n"
                        + "  exit 42\n"
                        + "fi\n"
                        + "echo direct-blocked\n").replace("__PORT__", String.valueOf(port));
                ExecResult r = Exec.run(backend, ExecRequest.of(List.of("/bin/sh", "-c", script), w));
                assertEquals(0, r.exitCode(), "exit=" + r.exitCode() + "\nstdout=" + r.stdout() + "\nstderr=" + r.stderr());
                assertTrue(r.stdout().contains("hello bridge"));
                assertTrue(r.stdout().contains("direct-blocked"));
                assertTrue(serve.get(5, TimeUnit.SECONDS).startsWith("GET http://127.0.0.1:" + port + "/allowed HTTP/"));
            });
        }
    }
}
