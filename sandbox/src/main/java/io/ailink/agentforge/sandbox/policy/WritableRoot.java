package io.ailink.agentforge.sandbox.policy;

import java.util.List;

/**
 * 一个可写根（Codex WritableRoot）：根下除只读子路径、受保护名之外都可写。
 *
 * @param root                   可写根（规范化的绝对路径）
 * @param readOnlySubpaths       根下保持只读的子路径（存在的受保护名、gitdir、显式 read / deny 条目）
 * @param protectedMetadataNames 根下第一层保持只读的名字（{@code .git} 等，不论存在与否）
 */
public record WritableRoot(String root, List<String> readOnlySubpaths, List<String> protectedMetadataNames) {
    public WritableRoot {
        readOnlySubpaths = List.copyOf(readOnlySubpaths);
        protectedMetadataNames = List.copyOf(protectedMetadataNames);
    }

    /** 可写根的判定：在根下、不在只读子路径下、第一层分量不是受保护名（Codex is_path_writable）。 */
    public boolean isWritable(String path) {
        String pp = LexicalPaths.normalize(path);
        if (!LexicalPaths.isUnder(pp, root)) return false;
        for (String s : readOnlySubpaths) if (LexicalPaths.isUnder(pp, s)) return false;
        for (String n : protectedMetadataNames) if (LexicalPaths.isUnder(pp, LexicalPaths.join(root, n))) return false;
        return true;
    }
}
