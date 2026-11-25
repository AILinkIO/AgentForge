package io.ailink.agentforge.sandbox.approval;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 会话审批缓存：{@link ApprovalDecision#APPROVE_FOR_SESSION} 记下的键，之后不再问。实现须线程安全。 */
public interface ApprovalCache {
    boolean isApproved(String key);

    void approve(String key);

    /** 线程安全的内存实现。 */
    static InMemory inMemory() {
        return new InMemory();
    }

    /** 内存实现（{@link #keys()} 给测试读）。 */
    final class InMemory implements ApprovalCache {
        private final Set<String> keys = ConcurrentHashMap.newKeySet();

        @Override
        public boolean isApproved(String key) {
            return keys.contains(key);
        }

        @Override
        public void approve(String key) {
            keys.add(key);
        }

        public Set<String> keys() {
            return Set.copyOf(keys);
        }
    }
}
