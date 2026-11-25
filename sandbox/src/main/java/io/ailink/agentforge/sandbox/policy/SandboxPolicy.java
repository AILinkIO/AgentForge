package io.ailink.agentforge.sandbox.policy;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.SandboxException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 沙盒策略（取 Codex 新版条目式形状 FileSystemSandboxPolicy + NetworkSandboxPolicy；移植自 codex-rs/protocol/src/permissions.rs，
 * Apache-2.0）。不可变数据：
 * <ul>
 * <li>文件系统按<b>最具体的条目</b>定访问级别（同样具体按 {@code READ < WRITE < DENY} 取大）；没有条目覆盖 ⇒ 拒；</li>
 * <li>可写根里的受保护名（{@code .git} 等）仍只读 —— 防模型改 hook / 凭据；</li>
 * <li>路径判定一律词法（不跟符号链接；后端自己解真实目标）。</li>
 * </ul>
 * 要在具体上下文里判定路径，用 {@link #matcher(PathContext)}。
 *
 * @param fileSystem        文件系统模式
 * @param entries           条目（只在 {@link FileSystemMode#RESTRICTED} 下生效）
 * @param network           网络模式
 * @param protectedNames    可写根下保持只读的名字
 * @param hostMetadataNames 宿主自己的元数据目录名（在项目根下即使不存在也保护）
 */
public record SandboxPolicy(FileSystemMode fileSystem, List<PolicyEntry> entries, NetworkMode network,
                            List<String> protectedNames, List<String> hostMetadataNames) {

    /** 缺省受保护名：版本库元数据、跨工具的智能体目录、AWS 凭据配置（Codex PROTECTED_METADATA_PATH_NAMES 去掉宿主自己的 .codex）。 */
    public static final List<String> DEFAULT_PROTECTED_NAMES = List.of(".git", ".agents", ".aws");

    /** 缺省：受限、无条目、断网、缺省受保护名。 */
    public static final SandboxPolicy DEFAULT = new SandboxPolicy(FileSystemMode.RESTRICTED, List.of(), NetworkMode.RESTRICTED,
            DEFAULT_PROTECTED_NAMES, List.of());

    public SandboxPolicy {
        Objects.requireNonNull(fileSystem, "fileSystem");
        Objects.requireNonNull(network, "network");
        entries = entries == null ? List.of() : List.copyOf(entries);
        protectedNames = protectedNames == null ? List.of() : List.copyOf(protectedNames);
        hostMetadataNames = hostMetadataNames == null ? List.of() : List.copyOf(hostMetadataNames);
    }

    // ── 构造 ─────────────────────────────────────────────────────────

    /** 条目式（缺省断网）。 */
    public static SandboxPolicy restricted(List<PolicyEntry> entries) {
        return restricted(entries, NetworkMode.RESTRICTED);
    }

    public static SandboxPolicy restricted(List<PolicyEntry> entries, NetworkMode network) {
        return new SandboxPolicy(FileSystemMode.RESTRICTED, entries, network, DEFAULT_PROTECTED_NAMES, List.of());
    }

    /** 不限文件系统、放行网络。 */
    public static SandboxPolicy dangerFullAccess() {
        return new SandboxPolicy(FileSystemMode.UNRESTRICTED, List.of(), NetworkMode.ENABLED, DEFAULT_PROTECTED_NAMES, List.of());
    }

    /** 全盘只读（断网）。 */
    public static SandboxPolicy readOnly() {
        return readOnly(false);
    }

    public static SandboxPolicy readOnly(boolean networkAccess) {
        return restricted(List.of(PolicyEntry.of(PolicyPath.special(SpecialKind.ROOT), AccessMode.READ)), net(networkAccess));
    }

    /** 已在外部沙盒里：不限文件系统，网络按参数。 */
    public static SandboxPolicy externalSandbox(boolean networkAccess) {
        return new SandboxPolicy(FileSystemMode.EXTERNAL, List.of(), net(networkAccess), DEFAULT_PROTECTED_NAMES, List.of());
    }

    /** {@link #workspaceWrite(List, WorkspaceWriteOptions)} 的选项（Codex WorkspaceWrite 的开关）。 */
    public record WorkspaceWriteOptions(boolean networkAccess, boolean excludeTmpdir, boolean excludeSlashTmp) {
        public static final WorkspaceWriteOptions DEFAULT = new WorkspaceWriteOptions(false, false, false);
        /** 不加 /tmp 与 $TMPDIR（测试与严格场景常用）。 */
        public static final WorkspaceWriteOptions NO_TEMP = new WorkspaceWriteOptions(false, true, true);
    }

    public static SandboxPolicy workspaceWrite() {
        return workspaceWrite(List.of(), WorkspaceWriteOptions.DEFAULT);
    }

    /**
     * 全盘只读 + 项目根可写 + /tmp、$TMPDIR 可写（各自可排除）+ {@code writableRoots}（必须是绝对路径）
     * （Codex WorkspaceWrite{writable_roots, network_access, exclude_tmpdir_env_var, exclude_slash_tmp}）。
     */
    public static SandboxPolicy workspaceWrite(List<String> writableRoots, WorkspaceWriteOptions options) {
        List<PolicyEntry> es = new ArrayList<>();
        es.add(PolicyEntry.of(PolicyPath.special(SpecialKind.ROOT), AccessMode.READ));
        es.add(PolicyEntry.of(PolicyPath.special(SpecialKind.PROJECT_ROOTS), AccessMode.WRITE));
        if (!options.excludeSlashTmp()) es.add(PolicyEntry.of(PolicyPath.special(SpecialKind.SLASH_TMP), AccessMode.WRITE));
        if (!options.excludeTmpdir()) es.add(PolicyEntry.of(PolicyPath.special(SpecialKind.TMPDIR), AccessMode.WRITE));
        if (writableRoots != null) {
            for (String r : writableRoots) {
                if (!LexicalPaths.isAbsolute(r)) throw SandboxException.of(ErrorKind.ARGUMENT, "sandbox/path-not-absolute", r);
                es.add(PolicyEntry.of(r, AccessMode.WRITE));
            }
        }
        return restricted(es, net(options.networkAccess()));
    }

    private static NetworkMode net(boolean enabled) {
        return enabled ? NetworkMode.ENABLED : NetworkMode.RESTRICTED;
    }

    public SandboxPolicy withEntries(List<PolicyEntry> value) {
        return new SandboxPolicy(fileSystem, value, network, protectedNames, hostMetadataNames);
    }

    /** 追加条目。 */
    public SandboxPolicy plusEntries(PolicyEntry... more) {
        List<PolicyEntry> es = new ArrayList<>(entries);
        es.addAll(List.of(more));
        return withEntries(es);
    }

    public SandboxPolicy withNetwork(NetworkMode value) {
        return new SandboxPolicy(fileSystem, entries, value, protectedNames, hostMetadataNames);
    }

    public SandboxPolicy withProtectedNames(List<String> value) {
        return new SandboxPolicy(fileSystem, entries, network, value, hostMetadataNames);
    }

    public SandboxPolicy withHostMetadataNames(List<String> value) {
        return new SandboxPolicy(fileSystem, entries, network, protectedNames, value);
    }

    // ── 与上下文无关的判定 ───────────────────────────────────────────

    public boolean isRestricted() {
        return fileSystem == FileSystemMode.RESTRICTED;
    }

    /** 受保护名全集：protectedNames 后接 hostMetadataNames，去重保序。 */
    public List<String> allProtectedNames() {
        List<String> all = new ArrayList<>(protectedNames);
        all.addAll(hostMetadataNames);
        return LexicalPaths.distinct(all);
    }

    public boolean fullNetworkAccess() {
        return network == NetworkMode.ENABLED;
    }

    static boolean isSpecial(PolicyEntry e, SpecialKind kind) {
        return e.path() instanceof PolicyPath.Special s && s.kind() == kind;
    }

    private boolean rootAccess(java.util.function.Predicate<AccessMode> pred) {
        if (!isRestricted()) return false;
        for (PolicyEntry e : entries) if (isSpecial(e, SpecialKind.ROOT) && pred.test(e.access())) return true;
        return false;
    }

    /** 有 deny 条目（Windows 上 /tmp 那条不算）⇒ 只有沙盒能兑现 ⇒ 永不裸跑。 */
    public boolean hasDeniedReadRestrictions() {
        if (!isRestricted()) return false;
        for (PolicyEntry e : entries)
            if (e.access() == AccessMode.DENY && !(LexicalPaths.WINDOWS && isSpecial(e, SpecialKind.SLASH_TMP))) return true;
        return false;
    }

    /** 不带沙盒跑仍能兑现这份策略（没有 deny-read）。 */
    public boolean unsandboxedExecutionAllowed() {
        return !hasDeniedReadRestrictions();
    }

    /** 全盘可读：不限 / 外部沙盒；或根可读且没有 deny 条目。 */
    public boolean fullDiskReadAccess() {
        return !isRestricted() || (rootAccess(a -> a != AccessMode.DENY) && !hasDeniedReadRestrictions());
    }

    private static String trimPath(String path) {
        return LexicalPaths.isAbsolute(path) ? LexicalPaths.normalize(path) : path;
    }

    private static boolean specialMatchesLiteral(PolicyPath.Special s, String path) {
        return switch (s.kind()) {
            case ROOT -> LexicalPaths.isAbsolute(path) && LexicalPaths.isFileSystemRoot(LexicalPaths.normalize(path));
            case SLASH_TMP -> !LexicalPaths.WINDOWS && LexicalPaths.isAbsolute(path) && "/tmp".equals(LexicalPaths.normalize(path));
            default -> false;
        };
    }

    /** 不靠 cwd 就能判定两条指向同一目标（Codex file_system_paths_share_target）。 */
    private static boolean sharesTarget(PolicyPath a, PolicyPath b) {
        if (a instanceof PolicyPath.Literal la && b instanceof PolicyPath.Literal lb)
            return LexicalPaths.same(trimPath(la.path()), trimPath(lb.path()));
        if (a instanceof PolicyPath.Special sa && b instanceof PolicyPath.Special sb)
            return sa.kind() == sb.kind() && (sa.kind() != SpecialKind.PROJECT_ROOTS || Objects.equals(sa.subpath(), sb.subpath()));
        if (a instanceof PolicyPath.Literal la && b instanceof PolicyPath.Special sb) return specialMatchesLiteral(sb, la.path());
        if (a instanceof PolicyPath.Special sa && b instanceof PolicyPath.Literal lb) return specialMatchesLiteral(sa, lb.path());
        if (a instanceof PolicyPath.GlobPattern ga && b instanceof PolicyPath.GlobPattern gb) return ga.pattern().equals(gb.pattern());
        return false;
    }

    private boolean sameTargetWriteOverride(PolicyEntry e) {
        for (PolicyEntry o : entries)
            if (o.access() == AccessMode.WRITE && o.access().rank() > e.access().rank() && sharesTarget(o.path(), e.path())) return true;
        return false;
    }

    private boolean hasWriteNarrowingEntries() {
        for (PolicyEntry e : entries) {
            if (e.access() == AccessMode.WRITE) continue;
            boolean narrows = switch (e.path()) {
                case PolicyPath.Literal l -> !sameTargetWriteOverride(e);
                case PolicyPath.GlobPattern g -> true;
                case PolicyPath.Special s -> {
                    if (s.kind() == SpecialKind.ROOT) yield e.access() == AccessMode.DENY;
                    if (s.kind() == SpecialKind.SLASH_TMP && LexicalPaths.WINDOWS) yield false;
                    if (s.kind() == SpecialKind.MINIMAL) yield false;
                    yield !sameTargetWriteOverride(e);
                }
            };
            if (narrows) return true;
        }
        return false;
    }

    /** 全盘可写：不限 / 外部沙盒；或根可写且没有真正收窄它的条目（同目标的 write 盖过 read 不算收窄）。 */
    public boolean fullDiskWriteAccess() {
        return !isRestricted() || (rootAccess(a -> a == AccessMode.WRITE) && !hasWriteNarrowingEntries());
    }

    /** 后端要不要加平台缺省可读目录（有可读的 MINIMAL 且不是全盘可读）。 */
    public boolean includePlatformDefaults() {
        if (fullDiskReadAccess() || !isRestricted()) return false;
        for (PolicyEntry e : entries) if (isSpecial(e, SpecialKind.MINIMAL) && e.access() != AccessMode.DENY) return true;
        return false;
    }

    public boolean requiresPlatformSandbox() {
        return requiresPlatformSandbox(false);
    }

    /**
     * 要不要平台沙盒（Codex should_require_platform_sandbox）：有托管网络要求 ⇒ 要；断网 ⇒ 除外部沙盒外都要；
     * 放行网络 ⇒ 受限且不是全盘可写才要。{@code PROXIED} 视同托管网络要求。
     */
    public boolean requiresPlatformSandbox(boolean managedNetwork) {
        if (managedNetwork || network == NetworkMode.PROXIED) return true;
        if (network != NetworkMode.ENABLED) return fileSystem != FileSystemMode.EXTERNAL;
        return isRestricted() && !fullDiskWriteAccess();
    }

    /** 批准过的升级命令用的策略（Codex for_approved_command）：根可写，但保留全部 deny 条目与网络。不受限的策略原样返回。 */
    public SandboxPolicy forApprovedCommand() {
        if (!isRestricted()) return this;
        LinkedHashSet<PolicyEntry> es = new LinkedHashSet<>();
        es.add(PolicyEntry.of(PolicyPath.special(SpecialKind.ROOT), AccessMode.WRITE));
        for (PolicyEntry e : entries) if (e.access() == AccessMode.DENY) es.add(e);
        return withEntries(new ArrayList<>(es));
    }

    /** 这份策略在一个上下文里解析好的匹配器（不可变）。 */
    public PolicyMatcher matcher(PathContext context) {
        return new PolicyMatcher(this, context);
    }

    /** 不变量校验（≙ keelson 的 spec）：受保护名不能为空串。不合 ⇒ {@link ErrorKind#ARGUMENT}。类型本身已保证字段非空、枚举合法。 */
    public SandboxPolicy validate() {
        for (String n : protectedNames)
            if (n == null || n.isEmpty()) throw SandboxException.of(ErrorKind.ARGUMENT, "sandbox/invalid-policy-json", "empty protected name");
        for (String n : hostMetadataNames)
            if (n == null || n.isEmpty()) throw SandboxException.of(ErrorKind.ARGUMENT, "sandbox/invalid-policy-json", "empty host metadata name");
        return this;
    }
}
