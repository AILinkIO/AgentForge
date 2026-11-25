package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.auth.AuthStore;
import io.ailink.agentforge.agent.auth.Keystore;
import io.ailink.agentforge.agent.auth.SecretKeys;
import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.models.Catalog;
import io.ailink.agentforge.agent.models.Models;
import io.ailink.agentforge.agent.models.Prefs;
import io.ailink.agentforge.agent.models.SelectedProviders;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.session.ModelBackend;
import io.ailink.agentforge.agent.session.SessionConfig;
import io.ailink.agentforge.agent.store.InMemorySessionStore;
import io.ailink.agentforge.tui.Editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 无头测试台：临时配置目录 + 假模型 + 内存库的运行时，编辑器 + 工作台；{@link #pump} 排空 post 队列直到条件成立。 */
final class Harness implements AutoCloseable {
    final Path cfg, root;
    final FakeModel fm;
    final AgentRuntime rt;
    final Editor ed;
    final AgentWorkbench wb;
    final AtomicLong clock = new AtomicLong(1_000_000);

    Harness(Path tmp, FakeModel fm) throws Exception { this(tmp, fm, b -> { }, new AgentWorkbench.Options().requireModel(false), 100, 32); }

    Harness(Path tmp, FakeModel fm, Consumer<AgentRuntime.Builder> tweak, AgentWorkbench.Options opts, int cols, int rows) throws Exception {
        cfg = Files.createDirectories(tmp.resolve("cfg"));
        root = Files.createDirectories(tmp.resolve("ws"));
        this.fm = fm;
        AuthStore auth = new AuthStore(cfg.resolve("auth.json.enc"), new SecretKeys(cfg.resolve(".auth-key"), Keystore.NONE));
        Models models = new Models(new Catalog(cfg.resolve("models.json")), new SelectedProviders(cfg.resolve("providers.json")),
                new Prefs(cfg.resolve("agent.json")), auth);
        AgentRuntime.Builder b = AgentRuntime.builder().configDir(cfg).workspaceRoot(root).models(models)
                .backend(ModelBackend.of(fm)).config(SessionConfig.defaults().retryInitialDelayMs(1))
                .store(new InMemorySessionStore()).startMcp(false);
        tweak.accept(b);
        rt = b.build();
        ed = new Editor(cols, rows);
        ed.setClock(clock::get);
        ed.setErrorLogger(Throwable::printStackTrace);
        wb = AgentWorkbench.install(ed, rt, opts.persistHistory(false));
    }

    /** 排空 post 队列、跑定时器，直到 cond 成立（最多 10 秒）。 */
    Harness pump(BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            ed.drainPosted();
            ed.runTimers();
            if (cond.getAsBoolean()) { ed.drainPosted(); return this; }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等不到条件；屏幕：\n" + screen() + "\n记录区：\n" + text());
            }
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** 等这一轮跑完（不忙、post 队列排空）。 */
    Harness idle() {
        pump(() -> !wb.session().busy());
        try { wb.session().awaitIdle(java.time.Duration.ofSeconds(10)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        ed.drainPosted();
        wb.refresh();
        return this;
    }

    /** 时钟往前拨 ms 并跑定时器（补全自动弹出等）。 */
    Harness advance(long ms) {
        clock.addAndGet(ms);
        ed.runTimers();
        ed.drainPosted();
        return this;
    }

    Harness type(String s) { ed.typeText(s); ed.drainPosted(); return this; }

    Harness keys(String k) { ed.feed(k); ed.drainPosted(); return this; }

    String screen() { return ed.render().dump(); }

    String text() { return wb.transcriptView().buffer().contents(); }

    @Override
    public void close() {
        try { wb.session().interrupt(); } catch (RuntimeException ignored) { }
        wb.shutdown();
        rt.close();
        AgentPaths.override(null);
    }
}
