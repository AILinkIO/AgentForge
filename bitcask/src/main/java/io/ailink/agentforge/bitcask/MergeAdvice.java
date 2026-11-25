package io.ailink.agentforge.bitcask;

import java.util.List;

/**
 * merge 策略的建议（{@link Bitcask#needsMerge}）：{@code files} 可直接交给 {@link Bitcask#merge(List)}。
 * ⚠️ active 写文件永不在候选里：缺省 2 GiB 文件上限下小库恒答「不需要」。
 */
public record MergeAdvice(boolean needed, List<String> files) {
}
