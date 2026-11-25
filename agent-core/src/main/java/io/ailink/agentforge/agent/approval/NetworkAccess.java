package io.ailink.agentforge.agent.approval;

import io.ailink.agentforge.sandbox.approval.ApprovalMode;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.proxy.HostPolicy;

import java.net.URI;
import java.util.List;

/**
 * 进程内出网（web-fetch / web-search）的网络策略：与子进程沙盒同一个开关（{@link PermissionSettings#networkEnabled()}），
 * 加上规则文件里的 {@code network_rule}（放行 / 拉黑主机）与本会话放行过的主机。照 Codex 的网络审批：
 * 拉黑的一律拒；网络开着或主机被放行 ⇒ 直接放；否则问（审批策略不许问 ⇒ 拒）。
 */
public final class NetworkAccess {
    private NetworkAccess() { }

    /** 判决。 */
    public sealed interface Verdict {
        record Allow() implements Verdict { }

        record Ask() implements Verdict { }

        record Forbid(String reason) implements Verdict { }
    }

    /** URL 的主机（规范化）；解析不出 ⇒ null。 */
    public static String hostOf(String url) {
        if (url == null) return null;
        try {
            String h = URI.create(url.strip()).getHost();
            if (h == null || h.isEmpty()) return null;
            String n = HostPolicy.normalizeHost(h);
            return n.isEmpty() ? null : n;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Verdict decide(SessionPermissions perms, String host) {
        if (host == null) return new Verdict.Allow();     // 交给工具自己报 URL 错
        List<List<String>> lists = perms.execPolicy().current().compiledNetworkDomains();
        HostPolicy hp = new HostPolicy(lists.get(0), lists.get(1), HostPolicy.Mode.FULL, HostPolicy.Fallback.ASK);
        HostPolicy.Decision d = hp.decide(host);
        if (d == HostPolicy.Decision.DENY) return new Verdict.Forbid("规则禁止访问 " + host + "（network_rule 拉黑了它）");
        PermissionSettings s = perms.settings();
        if (s.networkEnabled() || d == HostPolicy.Decision.ALLOW || perms.approvedForSession(sessionKey(host))) return new Verdict.Allow();
        ApprovalPolicy ap = s.approval();
        if (ap.mode() == ApprovalMode.NEVER || (ap.mode() == ApprovalMode.GRANULAR && !ap.allowsSandboxApproval()))
            return new Verdict.Forbid("网络关着（沙盒策略 " + s.sandbox().wire() + " 断网），审批策略 " + ap.wire() + " 不允许询问；访问 " + host
                    + " 被拒。需要的话请用户开网络（/network on）或放行这个主机。");
        return new Verdict.Ask();
    }

    public static String sessionKey(String host) {
        return "host:" + host;
    }
}
