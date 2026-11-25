package io.ailink.agentforge.sandbox.command;

import io.ailink.agentforge.sandbox.Platform;

import java.util.List;
import java.util.Set;

/**
 * 命令分类（移植自 Codex shell-command/src/command_safety，Apache-2.0）：
 * <ul>
 * <li>{@link #isKnownSafe}：只读、无副作用的已知命令（is_safe_command.rs —— 上游 2026-08 随 untrusted 一起退役了这张白名单，
 *     这里保留它，untrusted 模式与「没有沙盒后端」时靠它免掉读命令的审批）；</li>
 * <li>{@link #dangerousMatch}：明显危险的命令（is_dangerous_command.rs：{@code rm -f} 一族，穿透 sudo / env / trap 与任意 shell 语法）。</li>
 * </ul>
 * 只按 POSIX 语义判；Windows 的 PowerShell 规则不移植。
 */
public final class CommandSafety {
    private CommandSafety() {
    }

    /** 命中的危险规则。 */
    public enum DangerousMatch {
        /** 带 force 选项的 rm。 */
        FORCED_RM,
        /** 其他（包括包装层太深、失败即拒）。 */
        OTHER
    }

    static final int MAX_DANGEROUS_COMMAND_WRAPPER_DEPTH = 8;

    /** POSIX 下的可执行名：最后一个 '/' 之后；空 ⇒ {@code null}。 */
    static String executableName(String raw) {
        if (raw == null) return null;
        int i = raw.lastIndexOf('/');
        String name = i >= 0 ? raw.substring(i + 1) : raw;
        return name.isEmpty() ? null : name;
    }

    // ══ 已知安全 ═════════════════════════════════════════════════════════

    private static final Set<String> SAFE = Set.of("cat", "cd", "cut", "echo", "expr", "false", "grep", "head", "id", "ls", "nl", "paste", "pwd",
            "rev", "seq", "stat", "tail", "tr", "true", "uname", "uniq", "wc", "which", "whoami");

    /** 已知安全：本身在白名单里，或是 {@code bash/zsh -lc "…"} 且里面每条纯命令都在白名单里（Codex is_known_safe_command）。 */
    public static boolean isKnownSafe(List<String> command) {
        if (command == null || command.isEmpty()) return false;
        List<String> cmd = command.stream().map(s -> s.equals("zsh") ? "bash" : s).toList();
        if (isSafeToCallWithExec(cmd)) return true;
        List<List<String>> all = BashCommands.parseShellLcPlainCommands(cmd);
        if (all == null || all.isEmpty()) return false;
        for (List<String> c : all) if (!isSafeToCallWithExec(c)) return false;
        return true;
    }

    /** 单条 argv 的白名单判定（Codex is_safe_to_call_with_exec）。 */
    public static boolean isSafeToCallWithExec(List<String> command) {
        if (command == null || command.isEmpty()) return false;
        String cmd = executableName(command.getFirst());
        if (cmd == null) return false;
        if (Platform.isLinux() && (cmd.equals("numfmt") || cmd.equals("tac"))) return true;
        if (SAFE.contains(cmd)) return true;
        List<String> args = command.subList(1, command.size());
        return switch (cmd) {
            case "base64" -> args.stream().noneMatch(a -> a.equals("-o") || a.equals("--output") || a.startsWith("--output=")
                    || (a.startsWith("-o") && !a.equals("-o")));
            // 能删文件、写文件或执行任意命令的选项
            case "find" -> command.stream().noneMatch(a -> Set.of("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fls", "-fprint", "-fprint0",
                    "-fprintf").contains(a));
            case "rg" -> command.stream().noneMatch(a -> a.equals("--search-zip") || a.equals("-z")
                    || a.equals("--pre") || a.startsWith("--pre=") || a.equals("--hostname-bin") || a.startsWith("--hostname-bin="));
            // 仓库配置能让只读的 git 命令也去执行辅助程序
            case "git" -> false;
            case "sed" -> command.size() <= 4 && command.size() > 1 && command.get(1).equals("-n")
                    && isValidSedNArg(command.size() > 2 ? command.get(2) : null);
            default -> false;
        };
    }

    /** {@code ^(\d+,)?\d+p$}。 */
    static boolean isValidSedNArg(String s) {
        if (s == null || !s.endsWith("p")) return false;
        String core = s.substring(0, s.length() - 1);
        String[] parts = core.split(",", -1);
        if (parts.length < 1 || parts.length > 2) return false;
        for (String part : parts) {
            if (part.isEmpty()) return false;
            for (int i = 0; i < part.length(); i++) if (!Character.isDigit(part.charAt(i)) || part.charAt(i) > '9') return false;
        }
        return true;
    }

    // ══ 危险 ═════════════════════════════════════════════════════════════

    /** 命中的危险规则；不危险 ⇒ {@code null}（Codex dangerous_command_match）。 */
    public static DangerousMatch dangerousMatch(List<String> command) {
        return dangerousWithDepth(command == null ? List.of() : command, 0);
    }

    public static boolean isDangerous(List<String> command) {
        return dangerousMatch(command) != null;
    }

    private static DangerousMatch dangerousWithDepth(List<String> command, int depth) {
        if (depth > MAX_DANGEROUS_COMMAND_WRAPPER_DEPTH) return DangerousMatch.OTHER;
        DangerousMatch m = dangerousForExec(command, depth);
        if (m != null) return m;
        // shell 脚本：任何一条字面命令危险都算（包括控制流 / 替换里的）
        List<List<String>> lits = BashCommands.parseShellLcLiteralCommands(command);
        if (lits != null) {
            for (List<String> c : lits) {
                DangerousMatch inner = dangerousWithDepth(c, depth + 1);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private static DangerousMatch dangerousForExec(List<String> command, int depth) {
        if (command.isEmpty()) return null;
        String cmd0 = executableName(command.getFirst());
        if (cmd0 == null) return null;
        List<String> rest = command.subList(1, command.size());
        return switch (cmd0) {
            case "rm" -> rmArgsIncludeForce(rest) ? DangerousMatch.FORCED_RM : null;
            // sudo <cmd>：看 <cmd>
            case "sudo" -> dangerousWithDepth(rest, depth + 1);
            case "env" -> dangerousForEnv(command, depth);
            case "trap" -> dangerousForTrap(command, depth);
            default -> null;
        };
    }

    /** 跳过 env 的赋值 / -i 再看它要跑的命令。 */
    private static DangerousMatch dangerousForEnv(List<String> command, int depth) {
        int i = 1;
        while (i < command.size()) {
            String a = command.get(i);
            if (a.equals("--")) {
                i++;
                break;
            }
            int eq = a.indexOf('=');
            if (a.equals("-i") || a.equals("--ignore-environment") || (eq > 0 && !a.startsWith("-"))) {
                i++;
                continue;
            }
            break;
        }
        return dangerousWithDepth(command.subList(Math.min(i, command.size()), command.size()), depth + 1);
    }

    /** trap 的动作是存在第一个操作数里的 shell 源码。 */
    private static DangerousMatch dangerousForTrap(List<String> command, int depth) {
        int i = 1;
        if (i < command.size() && command.get(i).equals("--")) i++;
        if (i >= command.size() || command.get(i).startsWith("-")) return null;
        return dangerousWithDepth(List.of("sh", "-c", command.get(i)), depth + 1);
    }

    static boolean rmArgsIncludeForce(List<String> args) {
        for (String a : args) {
            if (a.equals("--")) break;
            if (a.equals("--force")) return true;
            if (a.startsWith("-") && !a.startsWith("--") && a.indexOf('f') > 0) return true;
        }
        return false;
    }
}
