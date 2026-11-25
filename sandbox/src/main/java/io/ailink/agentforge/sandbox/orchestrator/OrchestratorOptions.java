package io.ailink.agentforge.sandbox.orchestrator;

import io.ailink.agentforge.sandbox.approval.ApprovalCache;
import io.ailink.agentforge.sandbox.approval.DenialClassifier;
import io.ailink.agentforge.sandbox.approval.EnvironmentMarkers;

/**
 * 编排器选项。
 *
 * @param classifier                     拒判（缺省 {@link DenialClassifier#DEFAULT}）
 * @param approvalCache                  会话审批缓存（可空 ⇒ 不缓存）
 * @param environmentMarkers             沙盒子进程的环境标记（可空）
 * @param runUnsandboxedWhenUnavailable  要沙盒但本机没有时裸跑（缺省 false = 拒；有 deny-read 时无论如何都拒）
 */
public record OrchestratorOptions(DenialClassifier classifier, ApprovalCache approvalCache, EnvironmentMarkers environmentMarkers,
                                  boolean runUnsandboxedWhenUnavailable) {
    public static final OrchestratorOptions DEFAULT = new OrchestratorOptions(null, null, null, false);

    public OrchestratorOptions {
        if (classifier == null) classifier = DenialClassifier.DEFAULT;
    }

    public OrchestratorOptions withApprovalCache(ApprovalCache v) {
        return new OrchestratorOptions(classifier, v, environmentMarkers, runUnsandboxedWhenUnavailable);
    }

    public OrchestratorOptions withEnvironmentMarkers(EnvironmentMarkers v) {
        return new OrchestratorOptions(classifier, approvalCache, v, runUnsandboxedWhenUnavailable);
    }

    public OrchestratorOptions withRunUnsandboxedWhenUnavailable(boolean v) {
        return new OrchestratorOptions(classifier, approvalCache, environmentMarkers, v);
    }

    public OrchestratorOptions withClassifier(DenialClassifier v) {
        return new OrchestratorOptions(v, approvalCache, environmentMarkers, runUnsandboxedWhenUnavailable);
    }
}
