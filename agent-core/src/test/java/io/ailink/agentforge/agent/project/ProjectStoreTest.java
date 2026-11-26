package io.ailink.agentforge.agent.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectStoreTest {
    @TempDir Path cfg;
    @TempDir Path a;
    @TempDir Path b;

    @Test
    void createUpdateDeleteRoundTripThroughTheFile() {
        ProjectStore st = new ProjectStore(ProjectStore.file(cfg));
        Project p = st.create(" novels ", List.of(a.toString(), b.toString(), a.toString()), "用中文写", "x/y", "read-only");
        assertEquals("novels", p.name());
        assertEquals(List.of(a.toString(), b.toString()), p.folders(), "去重、规范化");
        assertEquals(a, p.root());
        assertEquals(b, p.folderOrDefault(b.toString()));
        assertEquals(a, p.folderOrDefault("/nowhere"), "不是它的源文件夹 ⇒ 缺省那个");
        assertTrue(p.contains(b.resolve("sub/file")));

        List<Project> seen = new ArrayList<>();
        st.onChange(seen::add);
        Project q = st.update(p.id(), "小说", List.of(b.toString()), "", null, null);
        assertEquals(p.created(), q.created());
        assertNull(q.instructions(), "空指令 = 没有");
        assertEquals(List.of(q), seen);

        ProjectStore again = new ProjectStore(ProjectStore.file(cfg));
        assertEquals(List.of(q), again.list(), "落盘后读回来一样");
        assertEquals(q, again.byFolder(b).orElseThrow());

        assertTrue(again.delete(q.id()));
        assertTrue(new ProjectStore(ProjectStore.file(cfg)).list().isEmpty());
    }

    @Test
    void rejectsBadInput() {
        ProjectStore st = new ProjectStore(ProjectStore.file(cfg));
        assertThrows(IllegalArgumentException.class, () -> st.create("", List.of(a.toString()), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> st.create("x", List.of(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> st.create("x", List.of(a.resolve("missing").toString()), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> st.create("x", List.of(a.toString()), null, null, "bogus"));
        assertThrows(IllegalArgumentException.class, () -> st.update("nope", "x", List.of(a.toString()), null, null, null));
    }

    @Test
    void brokenFileIsReportedAndNeverOverwritten() throws Exception {
        Path f = ProjectStore.file(cfg);
        Files.writeString(f, "{not json");
        ProjectStore st = new ProjectStore(f);
        assertNotNull(st.broken());
        assertTrue(st.list().isEmpty());
        assertThrows(IllegalStateException.class, () -> st.create("x", List.of(a.toString()), null, null, null));
        assertEquals("{not json", Files.readString(f), "手改坏的文件原样留着");
    }
}
