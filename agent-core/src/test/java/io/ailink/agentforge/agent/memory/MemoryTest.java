package io.ailink.agentforge.agent.memory;

import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.util.Fnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.ailink.agentforge.agent.util.ToolKit.call;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MemoryTest {
    @TempDir Path cfg;
    @TempDir Path proj;

    @BeforeEach
    void setup() {
        assumeTrue(System.getenv("AGENTFORGE_MEMORY") == null, "环境里设了 AGENTFORGE_MEMORY");
        AgentPaths.override(cfg);
    }

    @AfterEach
    void reset() { AgentPaths.override(null); }

    @Test
    void workspaceTag() {
        Path p = Path.of("/home/u/my proj");
        assertEquals("my-proj-" + Fnv.hex8("/home/u/my proj"), Memory.workspaceTag(p));
        assertEquals(Memory.workspaceTag(Path.of("/home/u/x")), Memory.workspaceTag(Path.of("/home/u/./y/../x")), "规范化之后同一个");
        assertNotEquals(Memory.workspaceTag(Path.of("/a/src")), Memory.workspaceTag(Path.of("/b/src")));
        assertNull(Memory.workspaceTag(null));
        assertEquals(cfg.resolve("memory"), Memory.root());
    }

    @Test
    void readWriteList() throws Exception {
        assertEquals(List.of(), Memory.list(proj));
        assertNull(Memory.read(proj, "build.md"));
        Path p = Memory.write(proj, "build.md", "用 gradle\r\n");
        assertTrue(p.startsWith(Memory.dirFor(proj)));
        assertEquals("用 gradle\n", Memory.read(proj, "build.md"));
        Memory.write(proj, "a.md", "x");
        Files.writeString(Memory.dirFor(proj).resolve("junk.txt"), "x");
        assertEquals(List.of("a.md", "build.md"), Memory.list(proj));
        assertNotNull(Memory.deleteFor(proj));
        assertEquals(List.of(), Memory.list(proj));
    }

    @Test
    void rejections() {
        assertThrows(RuntimeException.class, () -> Memory.write(proj, "notes.txt", "x"));
        assertThrows(RuntimeException.class, () -> Memory.write(proj, "../escape.md", "x"));
        assertThrows(RuntimeException.class, () -> Memory.write(proj, "big.md", "x".repeat(Memory.MAX_CHARS + 1)));
    }

    @Test
    void tools() {
        assertEquals("还没有笔记。", call(MemoryTools.memoryList(), proj).text());
        assertEquals("记下了：t.md", call(MemoryTools.memoryWrite(), proj, "name", "t.md", "content", "内容").text());
        assertEquals("t.md\n", call(MemoryTools.memoryList(), proj).text());
        assertEquals("内容", call(MemoryTools.memoryRead(), proj, "name", "t.md").text());
        assertEquals("没有这篇笔记：u.md", call(MemoryTools.memoryRead(), proj, "name", "u.md").text());
        ToolOutput bad = call(MemoryTools.memoryWrite(), proj, "name", "t.txt", "content", "x");
        assertTrue(bad.error());
        assertTrue(bad.text().contains("memory 里只放 markdown"));
        assertFalse(MemoryTools.memoryWrite().sensitive());
        assertFalse(MemoryTools.memoryWrite().readOnly());
        assertTrue(MemoryTools.memoryRead().readOnly());
    }
}
