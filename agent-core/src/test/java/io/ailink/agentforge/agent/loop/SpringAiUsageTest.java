package io.ailink.agentforge.agent.loop;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringAiUsageTest {

    /** anthropic-java 的 usage 形状：Optional&lt;Long&gt;。 */
    public static final class FakeNative {
        public Optional<Long> cacheReadInputTokens() { return Optional.of(1800L); }

        public Optional<Long> cacheCreationInputTokens() { return Optional.empty(); }
    }

    /** anthropic-java 的 MessageDeltaUsage 形状：inputTokens 也是 Optional&lt;Long&gt;。 */
    public static final class FakeDelta {
        public Optional<Long> inputTokens() { return Optional.of(5200L); }
    }

    /** anthropic-java 的 Usage（message_start）形状：inputTokens 是 long。 */
    public static final class FakeStart {
        public long inputTokens() { return 0L; }
    }

    @Test
    void readsInputTokensFromDeltaUsage() {
        assertEquals(5200, SpringAiModelCaller.nativeLong(new FakeDelta(), "inputTokens"));
        assertEquals(0, SpringAiModelCaller.nativeLong(new FakeStart(), "inputTokens"));
    }

    @Test
    void readsCacheFieldsFromNativeUsage() {
        assertEquals(1800, SpringAiModelCaller.nativeLong(new FakeNative(), "cacheReadInputTokens"));
        assertEquals(0, SpringAiModelCaller.nativeLong(new FakeNative(), "cacheCreationInputTokens"));
        assertEquals(0, SpringAiModelCaller.nativeLong(new FakeNative(), "noSuchMethod"));
        assertEquals(0, SpringAiModelCaller.nativeLong(null, "cacheReadInputTokens"));
    }
}
