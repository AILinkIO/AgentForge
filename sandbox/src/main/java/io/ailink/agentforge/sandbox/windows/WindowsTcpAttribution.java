package io.ailink.agentforge.sandbox.windows;

import io.ailink.agentforge.sandbox.ffm.WinTcp;
import io.ailink.agentforge.sandbox.proxy.ProxyOptions;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Windows 回环 TCP 连接的受限令牌归因（照 Codex network-proxy/src/windows_tcp_attribution.rs）：把代理看到的回环四元组归因到
 * owner PID，再读该进程令牌的 TokenRestrictedSids。是「认得出沙盒连接」，不是强制流量走代理。查询失败一律回 false（fail closed）。⚠ 未验证。
 * 可直接当代理的连接守卫：{@link #connectionAuthorizer(String)}。
 */
public final class WindowsTcpAttribution {
    private WindowsTcpAttribution() {
    }

    /** IPv4 / IPv4 映射的 IPv6 ⇒ 4 字节；别的 ⇒ {@code null}。 */
    private static byte[] ipv4(InetSocketAddress ep) {
        if (ep == null) return null;
        InetAddress a = ep.getAddress();
        if (a instanceof Inet4Address) return a.getAddress();
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            for (int i = 0; i < 10; i++) if (b[i] != 0) return null;
            if (b[10] != -1 || b[11] != -1) return null;
            return Arrays.copyOfRange(b, 12, 16);
        }
        return null;
    }

    /** 四元组（source = local、destination = remote）的 owner PID；找不到 ⇒ 0。 */
    public static int owningProcessId(InetSocketAddress source, InetSocketAddress destination) {
        byte[] s = ipv4(source), d = ipv4(destination);
        return s != null && d != null ? WinTcp.owningProcessId(s, source.getPort(), d, destination.getPort()) : 0;
    }

    /** 进程令牌的受限 SID；失败 ⇒ {@code null}。 */
    public static List<String> restrictedSids(int pid) {
        return WinTcp.restrictedSids(pid);
    }

    /** 这条回环连接的 owner 令牌带 {@code expectedSid}。 */
    public static boolean isRestrictedProcess(InetSocketAddress source, InetSocketAddress destination, String expectedSid) {
        if (expectedSid == null || expectedSid.isBlank()) return false;
        int pid;
        try {
            pid = owningProcessId(source, destination);
        } catch (Throwable t) {
            pid = 0;
        }
        if (pid <= 0) return false;
        List<String> sids;
        try {
            sids = restrictedSids(pid);
        } catch (Throwable t) {
            return false;
        }
        return sids != null && sids.stream().anyMatch(x -> x.equalsIgnoreCase(expectedSid));
    }

    /** 代理的连接守卫：只放行带 {@code expectedSid} 的受限进程。 */
    public static Predicate<ProxyOptions.ConnectionInfo> connectionAuthorizer(String expectedSid) {
        return info -> isRestrictedProcess(info.source(), info.destination(), expectedSid);
    }
}
