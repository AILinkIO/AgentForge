package io.ailink.agentforge.sandbox.execpolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一组规则文件的当前策略 + 修订（移植自 Codex core/src/exec_policy.rs 的 ExecPolicyManager / load_exec_policy，Apache-2.0）。
 * <ul>
 * <li>加载顺序（低 → 高优先，后者的规则追加在后；判决按「多条取最严」，所以顺序只影响报错与列表）：内建规则文本 → 各受信目录里的
 *     {@code *.rules}（每个目录按文件名排序）→ 各<b>只收紧</b>目录里的（项目目录：仓库里带来的规则不可信，其中的 allow 规则丢掉、记一条警告，
 *     ≙ Codex 不加载未受信项目的规则）。目录不存在就跳过；某个文件写错 ⇒ 跳过它并记一条 {@link #warnings()}（上游是整体退回空策略）。</li>
 * <li>修订：「以后以它开头的命令不再问」往 {@link #amendmentFile()} 追加一行 {@code prefix_rule(…, decision="allow")} 并即时更新内存里的策略
 *     （已被 allow 规则覆盖的不重复加）；网络主机放行 / 拉黑同理追加 {@code network_rule}。</li>
 * </ul>
 * 线程安全：读是无锁快照；修订串行。
 */
public final class ExecPolicyManager {
    /** 规则文件后缀。 */
    public static final String RULE_EXTENSION = ".rules";
    /** 修订写进的文件名（与 Codex 相同）。 */
    public static final String DEFAULT_POLICY_FILE = "default.rules";

    private final String builtinIdentifier;
    private final String builtinRules;
    private final List<Path> ruleDirs;
    private final List<Path> restrictOnlyDirs;
    private final Path amendmentFile;
    private final AtomicReference<Policy> policy = new AtomicReference<>(Policy.empty());
    private volatile List<String> warnings = List.of();
    private volatile List<Path> files = List.of();
    private final Object updateLock = new Object();

    /**
     * @param builtinIdentifier 内建规则的名字（报错用；可空）
     * @param builtinRules      内建规则文本（可空）
     * @param ruleDirs          受信的规则目录（低 → 高）
     * @param amendmentFile     修订写进的文件（可空 ⇒ 不能修订）
     */
    public ExecPolicyManager(String builtinIdentifier, String builtinRules, List<Path> ruleDirs, Path amendmentFile) {
        this(builtinIdentifier, builtinRules, ruleDirs, List.of(), amendmentFile);
    }

    /**
     * @param restrictOnlyDirs 只收紧的规则目录（其中的 allow 规则不生效）
     */
    public ExecPolicyManager(String builtinIdentifier, String builtinRules, List<Path> ruleDirs, List<Path> restrictOnlyDirs, Path amendmentFile) {
        this.builtinIdentifier = builtinIdentifier == null ? "builtin.rules" : builtinIdentifier;
        this.builtinRules = builtinRules;
        this.ruleDirs = ruleDirs == null ? List.of() : List.copyOf(ruleDirs);
        this.restrictOnlyDirs = restrictOnlyDirs == null ? List.of() : List.copyOf(restrictOnlyDirs);
        this.amendmentFile = amendmentFile;
        reload();
    }

    /** 只有给定策略、不读盘（测试 / 嵌入用）。 */
    public static ExecPolicyManager of(Policy p) {
        ExecPolicyManager m = new ExecPolicyManager(null, null, List.of(), null);
        m.policy.set(Objects.requireNonNull(p));
        return m;
    }

    public Policy current() {
        return policy.get();
    }

    /** 上次加载时跳过的文件与原因（给人看）。 */
    public List<String> warnings() {
        return warnings;
    }

    /** 上次加载读进来的文件（不含内建）。 */
    public List<Path> files() {
        return files;
    }

    public List<Path> ruleDirs() {
        return ruleDirs;
    }

    public List<Path> restrictOnlyDirs() {
        return restrictOnlyDirs;
    }

    public Path amendmentFile() {
        return amendmentFile;
    }

    /** 目录里的 {@code *.rules}，按文件名排序；目录不存在 ⇒ 空。 */
    public static List<Path> collectPolicyFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) if (p.getFileName().toString().endsWith(RULE_EXTENSION) && Files.isRegularFile(p)) out.add(p);
        } catch (IOException e) {
            return out;
        }
        out.sort(null);
        return out;
    }

    /** 重新读全部规则。 */
    public void reload() {
        synchronized (updateLock) {
            List<String> warn = new ArrayList<>();
            List<Path> loaded = new ArrayList<>();
            PolicyParser parser = new PolicyParser();
            if (builtinRules != null && !builtinRules.isBlank()) {
                try {
                    parser.parse(builtinIdentifier, builtinRules);
                } catch (ExecPolicyException e) {
                    warn.add(e.getMessage());
                }
            }
            for (Path dir : ruleDirs) {
                for (Path f : collectPolicyFiles(dir)) {
                    String text;
                    try {
                        text = Files.readString(f, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        warn.add(f + ": 读不出来（" + e.getMessage() + "）");
                        continue;
                    }
                    // 写错的文件整份跳过：先在独立的解析器上试一遍，过了再并进来
                    try {
                        new PolicyParser().parse(f.toString(), text);
                        parser.parse(f.toString(), text);
                        loaded.add(f);
                    } catch (ExecPolicyException e) {
                        warn.add(e.getMessage());
                    }
                }
            }
            Policy built = parser.build();
            for (Path dir : restrictOnlyDirs) {
                for (Path f : collectPolicyFiles(dir)) {
                    try {
                        Policy p = new PolicyParser().parse(f.toString(), Files.readString(f, StandardCharsets.UTF_8)).build();
                        if (p.allowRuleCount() > 0)
                            warn.add(f + ": 项目规则里的 " + p.allowRuleCount() + " 条 allow 规则不生效（项目规则只能收紧；放行请写进用户规则目录）");
                        built = built.mergeOverlay(p.withoutAllowRules());
                        loaded.add(f);
                    } catch (IOException e) {
                        warn.add(f + ": 读不出来（" + e.getMessage() + "）");
                    } catch (ExecPolicyException e) {
                        warn.add(e.getMessage());
                    }
                }
            }
            policy.set(built);
            warnings = List.copyOf(warn);
            files = List.copyOf(loaded);
        }
    }

    /** 求值一条命令。 */
    public ExecApprovals.Result evaluate(ExecApprovals.Request req) {
        return ExecApprovals.evaluate(current(), req);
    }

    /** 已经被规则明确 allow 了（Codex append_amendment_and_update 里的 already_allowed）。 */
    public boolean alreadyAllowed(List<String> prefix) {
        Evaluation ev = current().check(prefix, c -> Decision.FORBIDDEN, true);
        return ev.decision() == Decision.ALLOW && ev.matchedRules().stream().anyMatch(m -> m.isPolicyMatch() && m.decision() == Decision.ALLOW);
    }

    /** 「以后以 {@code prefix} 开头的命令不再问」：追加到修订文件并更新内存。没有修订文件 ⇒ 只改内存（本进程有效）。 */
    public void appendAllowPrefixRule(List<String> prefix) {
        if (prefix == null || prefix.isEmpty()) throw new ExecPolicyException("prefix rule requires at least one token");
        synchronized (updateLock) {
            if (amendmentFile != null) Amend.appendAllowPrefixRule(amendmentFile, prefix);
            if (alreadyAllowed(prefix)) return;
            policy.set(current().withPrefixRule(prefix, Decision.ALLOW));
        }
    }

    /** 网络主机放行 / 拉黑（{@code decision} 为 ALLOW 或 FORBIDDEN）：追加 {@code network_rule} 并更新内存。 */
    public void appendNetworkRule(String host, String protocol, Decision decision, String justification) {
        synchronized (updateLock) {
            if (amendmentFile != null) Amend.appendNetworkRule(amendmentFile, host, protocol, decision, justification);
            policy.set(current().withNetworkRule(host, protocol, decision, justification));
        }
    }
}
