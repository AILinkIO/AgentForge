package io.ailink.agentforge.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** C# 的 Localization.AssertResourcesConsistent：中英两份文案键一致、占位符一致、都在 sandbox / exec / process 区。 */
class ResourcesTest {
    private static Set<String> placeholders(String s) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\\{(\\d+)(?::[^}]*)?}").matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void resourcesAreConsistent() {
        Properties zh = Messages.table(false), en = Messages.table(true);
        assertEquals(zh.stringPropertyNames(), en.stringPropertyNames());
        for (String k : zh.stringPropertyNames()) {
            assertTrue(k.startsWith("sandbox/") || k.startsWith("exec/") || k.startsWith("process/"), k);
            assertEquals(placeholders(zh.getProperty(k)), placeholders(en.getProperty(k)), k);
        }
        assertFalse(zh.getProperty("sandbox/bwrap-missing").isBlank());
    }

    @Test
    void apostrophesSurviveFormatting() {
        Message m = Messages.withLocale(java.util.Locale.ENGLISH, () -> Message.of("sandbox/read-outside-roots", "/x"));
        assertEquals("This path is outside the sandbox policy's readable roots: /x", m.text());
        assertEquals("沙盒路径必须是绝对路径：a", Messages.withLocale(java.util.Locale.SIMPLIFIED_CHINESE, () -> Message.of("sandbox/path-not-absolute", "a")).text());
    }
}
