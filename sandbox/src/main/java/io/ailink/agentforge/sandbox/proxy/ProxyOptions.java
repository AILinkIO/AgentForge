package io.ailink.agentforge.sandbox.proxy;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.approval.ApprovalCache;
import io.ailink.agentforge.sandbox.approval.ApprovalDecision;
import io.ailink.agentforge.sandbox.approval.ApprovalRequest;
import io.ailink.agentforge.sandbox.approval.Approver;

import java.net.InetSocketAddress;
import java.util.function.Predicate;

/**
 * 本地代理的选项。
 *
 * @param hosts                域名策略
 * @param connectionAuthorizer 连接先过它（看源 / 目的端点，如 Windows 下认受限令牌进程）；{@code null} ⇒ 都放；抛异常 ⇒ 拒
 * @param approver             策略回 ASK 时问谁（{@code null} ⇒ 拒）
 * @param approvals            会话审批缓存（可空）
 * @param connectTimeoutMs     上游连接超时（&gt; 0）
 */
public record ProxyOptions(HostPolicy hosts, Predicate<ConnectionInfo> connectionAuthorizer, Approver approver, ApprovalCache approvals,
                           long connectTimeoutMs) {
    /** 连接的端点：{@code source} = 客户端，{@code destination} = 代理监听端。 */
    public record ConnectionInfo(InetSocketAddress source, InetSocketAddress destination) {
    }

    public static final ProxyOptions DEFAULT = new ProxyOptions(HostPolicy.DEFAULT, null, null, null, 10_000);

    public ProxyOptions {
        if (hosts == null) hosts = HostPolicy.DEFAULT;
    }

    public static ProxyOptions of(HostPolicy hosts) {
        return new ProxyOptions(hosts, null, null, null, 10_000);
    }

    public ProxyOptions withConnectTimeoutMs(long v) {
        return new ProxyOptions(hosts, connectionAuthorizer, approver, approvals, v);
    }

    public ProxyOptions withConnectionAuthorizer(Predicate<ConnectionInfo> v) {
        return new ProxyOptions(hosts, v, approver, approvals, connectTimeoutMs);
    }

    public ProxyOptions withApprover(Approver v, ApprovalCache cache) {
        return new ProxyOptions(hosts, connectionAuthorizer, v, cache, connectTimeoutMs);
    }

    /** 审批键：{@code network:<协议>:<主机>}。 */
    public static String approvalKey(ProxyProtocol protocol, String host) {
        return "network:" + protocol.policyName() + ":" + HostPolicy.normalizeHost(host);
    }

    /** 策略回 ASK 时：会话缓存里有 ⇒ 放；否则问审批者（没有 ⇒ 拒）；APPROVE_FOR_SESSION ⇒ 记进缓存。 */
    public boolean approve(ProxyProtocol protocol, String host) {
        String k = approvalKey(protocol, host);
        if (approvals != null && approvals.isApproved(k)) return true;
        if (approver == null) return false;
        ApprovalDecision d = approver.decide(new ApprovalRequest(k, Message.of("sandbox/network-host-approval", host, protocol.policyName()), null, null));
        if (d == ApprovalDecision.APPROVE_FOR_SESSION && approvals != null) approvals.approve(k);
        return d == ApprovalDecision.APPROVE || d == ApprovalDecision.APPROVE_FOR_SESSION;
    }
}
