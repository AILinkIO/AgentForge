package io.ailink.agentforge.bitcask;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import io.ailink.agentforge.bitcask.ffm.BitcaskNative;
import io.ailink.agentforge.bitcask.ffm.NativeArena;
import io.ailink.agentforge.bitcask.ffm.NativeTypes;

/**
 * merge 策略（{@code bitcask_merge_policy_t}）。{@link #DEFAULT} = 上游 {@code bitcask_merge_policy_init} 的缺省（测试钉住两边一致）；
 * 改格用 {@code withXxx}。三个百分比格须在 [0, 100]（开库时、碰盘之前拦：{@code bitcask/merge-policy-percent}）。
 */
public record MergePolicy(int fragmentationTrigger, long deadBytesTrigger, int deletionRateTrigger, int fragmentationThreshold,
                          long deadBytesThreshold, long smallFileThreshold, int expirySeconds, int expiryGraceSeconds, long maxMergeSize) {

    public static final MergePolicy DEFAULT = new MergePolicy(60, 512L * 1024 * 1024, 0, 40, 128L * 1024 * 1024, 10L * 1024 * 1024, 0, 0, 0);

    public MergePolicy withFragmentationTrigger(int v) {
        return new MergePolicy(v, deadBytesTrigger, deletionRateTrigger, fragmentationThreshold, deadBytesThreshold, smallFileThreshold,
                expirySeconds, expiryGraceSeconds, maxMergeSize);
    }

    public MergePolicy withDeadBytesTrigger(long v) {
        return new MergePolicy(fragmentationTrigger, v, deletionRateTrigger, fragmentationThreshold, deadBytesThreshold, smallFileThreshold,
                expirySeconds, expiryGraceSeconds, maxMergeSize);
    }

    public MergePolicy withDeletionRateTrigger(int v) {
        return new MergePolicy(fragmentationTrigger, deadBytesTrigger, v, fragmentationThreshold, deadBytesThreshold, smallFileThreshold,
                expirySeconds, expiryGraceSeconds, maxMergeSize);
    }

    public MergePolicy withFragmentationThreshold(int v) {
        return new MergePolicy(fragmentationTrigger, deadBytesTrigger, deletionRateTrigger, v, deadBytesThreshold, smallFileThreshold,
                expirySeconds, expiryGraceSeconds, maxMergeSize);
    }

    public MergePolicy withDeadBytesThreshold(long v) {
        return new MergePolicy(fragmentationTrigger, deadBytesTrigger, deletionRateTrigger, fragmentationThreshold, v, smallFileThreshold,
                expirySeconds, expiryGraceSeconds, maxMergeSize);
    }

    public MergePolicy withSmallFileThreshold(long v) {
        return new MergePolicy(fragmentationTrigger, deadBytesTrigger, deletionRateTrigger, fragmentationThreshold, deadBytesThreshold, v,
                expirySeconds, expiryGraceSeconds, maxMergeSize);
    }

    public MergePolicy withExpirySeconds(int v) {
        return new MergePolicy(fragmentationTrigger, deadBytesTrigger, deletionRateTrigger, fragmentationThreshold, deadBytesThreshold,
                smallFileThreshold, v, expiryGraceSeconds, maxMergeSize);
    }

    public MergePolicy withExpiryGraceSeconds(int v) {
        return new MergePolicy(fragmentationTrigger, deadBytesTrigger, deletionRateTrigger, fragmentationThreshold, deadBytesThreshold,
                smallFileThreshold, expirySeconds, v, maxMergeSize);
    }

    public MergePolicy withMaxMergeSize(long v) {
        return new MergePolicy(fragmentationTrigger, deadBytesTrigger, deletionRateTrigger, fragmentationThreshold, deadBytesThreshold,
                smallFileThreshold, expirySeconds, expiryGraceSeconds, v);
    }

    private static final long FRAG_TRIGGER = NativeTypes.off(NativeTypes.MERGE_POLICY, "frag_merge_trigger");
    private static final long DEAD_TRIGGER = NativeTypes.off(NativeTypes.MERGE_POLICY, "dead_bytes_merge_trigger");
    private static final long DEL_TRIGGER = NativeTypes.off(NativeTypes.MERGE_POLICY, "deletion_rate_trigger");
    private static final long FRAG_THRESHOLD = NativeTypes.off(NativeTypes.MERGE_POLICY, "frag_threshold");
    private static final long DEAD_THRESHOLD = NativeTypes.off(NativeTypes.MERGE_POLICY, "dead_bytes_threshold");
    private static final long SMALL_FILE = NativeTypes.off(NativeTypes.MERGE_POLICY, "small_file_threshold");
    private static final long EXPIRY = NativeTypes.off(NativeTypes.MERGE_POLICY, "expiry_secs");
    private static final long GRACE = NativeTypes.off(NativeTypes.MERGE_POLICY, "expiry_grace_time");
    private static final long MAX_MERGE = NativeTypes.off(NativeTypes.MERGE_POLICY, "max_merge_size");

    /** 上游 {@code bitcask_merge_policy_init} 填的那份（测试用）。 */
    public static MergePolicy upstreamDefaults() {
        try (var a = new NativeArena()) {
            MemorySegment p = a.alloc(NativeTypes.MERGE_POLICY);
            BitcaskNative.mergePolicyInit(p);
            return new MergePolicy(p.get(ValueLayout.JAVA_INT, FRAG_TRIGGER), p.get(ValueLayout.JAVA_LONG, DEAD_TRIGGER),
                    p.get(ValueLayout.JAVA_INT, DEL_TRIGGER), p.get(ValueLayout.JAVA_INT, FRAG_THRESHOLD),
                    p.get(ValueLayout.JAVA_LONG, DEAD_THRESHOLD), p.get(ValueLayout.JAVA_LONG, SMALL_FILE),
                    p.get(ValueLayout.JAVA_INT, EXPIRY), p.get(ValueLayout.JAVA_INT, GRACE), p.get(ValueLayout.JAVA_LONG, MAX_MERGE));
        }
    }

    /** 百分比格须在 [0, 100]（{@code bitcask/merge-policy-percent}）。 */
    void validate() {
        check("fragmentationTrigger", fragmentationTrigger);
        check("deletionRateTrigger", deletionRateTrigger);
        check("fragmentationThreshold", fragmentationThreshold);
    }

    private static void check(String label, int v) {
        if (v < 0 || v > 100) throw BitcaskException.of("bitcask/merge-policy-percent", label, v);
    }

    MemorySegment toNative(NativeArena a) {
        MemorySegment p = a.alloc(NativeTypes.MERGE_POLICY);
        p.set(ValueLayout.JAVA_INT, FRAG_TRIGGER, fragmentationTrigger);
        p.set(ValueLayout.JAVA_LONG, DEAD_TRIGGER, deadBytesTrigger);
        p.set(ValueLayout.JAVA_INT, DEL_TRIGGER, deletionRateTrigger);
        p.set(ValueLayout.JAVA_INT, FRAG_THRESHOLD, fragmentationThreshold);
        p.set(ValueLayout.JAVA_LONG, DEAD_THRESHOLD, deadBytesThreshold);
        p.set(ValueLayout.JAVA_LONG, SMALL_FILE, smallFileThreshold);
        p.set(ValueLayout.JAVA_INT, EXPIRY, expirySeconds);
        p.set(ValueLayout.JAVA_INT, GRACE, expiryGraceSeconds);
        p.set(ValueLayout.JAVA_LONG, MAX_MERGE, maxMergeSize);
        return p;
    }
}
