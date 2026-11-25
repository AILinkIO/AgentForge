package io.ailink.agentforge.sandbox.execpolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 往规则文件末尾追加一条规则（移植自 Codex execpolicy/src/amend.rs，Apache-2.0）：建目录、加文件锁、必要时先补换行，只追加一行。
 * 阻塞 I/O。
 */
public final class Amend {
    private Amend() {
    }

    /** {@code prefix_rule(pattern=["git", "pull"], decision="allow")}。 */
    public static String allowPrefixRuleLine(List<String> prefix) {
        if (prefix == null || prefix.isEmpty()) throw new ExecPolicyException("prefix rule requires at least one token");
        List<String> toks = new ArrayList<>();
        for (String t : prefix) toks.add(jsonString(t));
        return "prefix_rule(pattern=[" + String.join(", ", toks) + "], decision=\"allow\")";
    }

    /** {@code network_rule(host="example.com", protocol="https", decision="allow"|"deny"|"prompt", justification=…)}。 */
    public static String networkRuleLine(String host, String protocol, Decision decision, String justification) {
        String h = NetworkRule.normalizeHost(host);
        String p = NetworkRule.normalizeProtocol(protocol);
        if (justification != null && justification.isBlank()) throw new ExecPolicyException("justification cannot be empty");
        String d = switch (decision) {
            case ALLOW -> "allow";
            case PROMPT -> "prompt";
            case FORBIDDEN -> "deny";
        };
        StringBuilder sb = new StringBuilder("network_rule(host=").append(jsonString(h)).append(", protocol=").append(jsonString(p))
                .append(", decision=").append(jsonString(d));
        if (justification != null) sb.append(", justification=").append(jsonString(justification));
        return sb.append(')').toString();
    }

    public static void appendAllowPrefixRule(Path file, List<String> prefix) {
        appendLine(file, allowPrefixRuleLine(prefix));
    }

    public static void appendNetworkRule(Path file, String host, String protocol, Decision decision, String justification) {
        appendLine(file, networkRuleLine(host, protocol, decision, justification));
    }

    /** 加锁追加一行：同样的行已经有了就不写；文件不以换行结尾时先补一个。 */
    public static void appendLine(Path file, String line) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock ignored = ch.lock()) {
                long size = ch.size();
                ByteBuffer all = ByteBuffer.allocate((int) size);
                while (all.hasRemaining() && ch.read(all, all.position()) > 0) { }
                String contents = new String(all.array(), 0, all.position(), StandardCharsets.UTF_8);
                if (contents.lines().anyMatch(line::equals)) return;   // 已经有这一行（Codex 同样去重）
                StringBuilder sb = new StringBuilder();
                if (!contents.isEmpty() && !contents.endsWith("\n")) sb.append('\n');
                sb.append(line).append('\n');
                ch.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)), size);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("规则文件写不进去：" + file, e);
        }
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
