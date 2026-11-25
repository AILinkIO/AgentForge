package io.ailink.agentforge.tui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CompletionTest {
    static final List<CompletionItem> CMDS = List.of(CompletionItem.of("/help", "帮助"), CompletionItem.of("/clear"), CompletionItem.of("/theme"));

    static Editor withSlash() {
        Editor ed = TestSupport.fresh("");
        ed.addCompletionSource(CompletionSource.triggered('/', true, null, p -> CMDS));
        return ed;
    }

    @Test
    void manualCompletionFilterAndAccept() {
        Editor ed = withSlash();
        ed.options().set("completion-auto", false);
        ed.typeText("/c").feed("M-TAB");
        assertTrue(ed.completionActive());
        assertEquals(List.of("/clear"), ed.completionCandidates().stream().map(CompletionItem::label).toList());
        String dump = ed.render().dump();
        assertTrue(dump.contains("/clear"), dump);
        ed.feed("TAB");
        assertEquals("/clear", TestSupport.text(ed));
        assertFalse(ed.completionActive());
    }

    @Test
    void autoPopupAfterTriggerWithFakeClock() {
        Editor ed = withSlash();
        AtomicLong now = new AtomicLong(1000);
        ed.setClock(now::get);
        ed.typeText("/");
        assertFalse(ed.completionActive());
        now.addAndGet(200);
        ed.runTimers();
        assertTrue(ed.completionActive(), "停 150ms 自动弹");
        assertEquals(3, ed.completionCandidates().size());
        ed.typeText("th");
        assertEquals("/theme", ed.completionCandidates().getFirst().label(), "开着时打字本地重新过滤");
        ed.feed("C-g");
        assertFalse(ed.completionActive());
        assertEquals("/th", TestSupport.text(ed));
        // 不在行首的 / 不管
        ed.typeText(" x/");
        now.addAndGet(200);
        ed.runTimers();
        assertFalse(ed.completionActive());
    }

    @Test
    void asyncSourceResultsArePostedBack() {
        Editor ed = TestSupport.fresh("");
        CompletableFuture<List<CompletionItem>> fut = new CompletableFuture<>();
        ed.addCompletionSource(new CompletionSource() {
            @Override public int start(Editor e) { return 0; }
            @Override public CompletableFuture<List<CompletionItem>> candidates(Editor e, String prefix) { return fut; }
        });
        ed.typeText("ab").feed("M-TAB");
        assertFalse(ed.completionActive());
        new Thread(() -> fut.complete(List.of(CompletionItem.of("abc"), CompletionItem.of("abd")))).start();
        long deadline = System.currentTimeMillis() + 5000;
        while (!ed.completionActive() && System.currentTimeMillis() < deadline) ed.drainPosted();
        assertTrue(ed.completionActive());
        ed.feed("C-n RET");
        assertEquals("abd", TestSupport.text(ed));
    }

    @Test
    void wordsFallback() {
        Editor ed = TestSupport.fresh("transcript transport ");
        ed.goTo(ed.currentBuffer().length());
        ed.addCompletionSource(CompletionSource.words(null));
        ed.typeText("tran").feed("M-TAB");
        assertTrue(ed.completionActive());
        assertEquals(2, ed.completionCandidates().size());
    }
}
