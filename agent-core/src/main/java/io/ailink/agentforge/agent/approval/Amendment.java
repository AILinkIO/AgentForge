package io.ailink.agentforge.agent.approval;

import io.ailink.agentforge.sandbox.execpolicy.Shlex;

import java.util.List;

/** 「以后都这样」时要写进去的东西：执行规则的前缀、网络主机、或一件工具。 */
public sealed interface Amendment {
    /** 给人看的一句（选项文案里用）。 */
    String describe();

    /** 往规则文件追加 {@code prefix_rule(pattern=[…], decision="allow")}：以这些词开头的命令以后免审批（且不再进沙盒）。 */
    record PrefixRule(List<String> prefix) implements Amendment {
        public PrefixRule {
            prefix = List.copyOf(prefix);
        }

        @Override
        public String describe() {
            // 拆不开的 `bash -lc "<脚本>"` 整条成了前缀（Codex 同样如此）：规则只匹配一字不差的同一条命令，
            // 说成"以 bash -lc 开头的命令"会让人以为放行了所有 shell 命令。
            if (shellWrapped()) return "与这条完全相同的命令";
            return "以 `" + Shlex.join(prefix) + "` 开头的命令";
        }

        /**
         * 前缀以解释器 / shell 入口开头且比它长（{@code bash -lc <脚本>}、{@code python3 -c <代码>} …）：规则覆盖整段脚本，
         * 只匹配一字不差的同一条。判据直接用 Codex 那张"不许当前缀建议"的表。
         */
        public boolean shellWrapped() {
            for (List<String> banned : io.ailink.agentforge.sandbox.execpolicy.ExecApprovals.BANNED_PREFIX_SUGGESTIONS) {
                if (banned.size() >= 2 && prefix.size() > banned.size() && prefix.subList(0, banned.size()).equals(banned)) return true;
            }
            return false;
        }
    }

    /** 往规则文件追加 {@code network_rule(host=…)}：放行或拉黑这个主机。 */
    record NetworkHost(String host) implements Amendment {
        @Override
        public String describe() {
            return "主机 " + host;
        }
    }

    /** 记进配置：这件工具以后免审批。 */
    record Tool(String name) implements Amendment {
        @Override
        public String describe() {
            return "工具 " + name;
        }
    }
}
