package io.ailink.agentforge.agent.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UtilTest {
    @Test
    void strbufAppendsAndCaches() {
        StrBuf b = new StrBuf();
        assertEquals("", b.text());
        for (int i = 0; i < 1000; i++) b.add("ab");
        assertEquals(2000, b.length());
        String t1 = b.text();
        assertSame(t1, b.text(), "没变就回缓存");
        b.add("c");
        assertTrue(b.text().endsWith("abc"));
        long e = b.epoch();
        b.reset();
        assertEquals("", b.text());
        assertEquals(e + 1, b.epoch());
        b.add("x");
        assertEquals("x", b.text());
        assertEquals(e + 1, b.epoch(), "追加不动世代号");
    }

    @Test
    void fnvMatchesReference() {
        assertEquals("811C9DC5", Fnv.hex8(""));
        assertEquals("E40C292C", Fnv.hex8("a"));
        assertEquals("BF9CF968", Fnv.hex8("foobar"));
    }

    @Test
    void fmtHelpers() {
        assertEquals("\"a\\\"b\"", Fmt.q("a\"b"));
        assertEquals(List.of("a", "b"), Fmt.lines("a\r\nb\n"));
        assertEquals(List.of("a", "", "b"), Fmt.lines("a\n\nb"));
        assertEquals("", Fmt.joinLines(List.of()));
    }
}
