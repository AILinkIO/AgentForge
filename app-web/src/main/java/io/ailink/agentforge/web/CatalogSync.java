package io.ailink.agentforge.web;

import io.ailink.agentforge.agent.models.Catalog;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 模型目录的同步任务：在后台跑（几 MB，慢网要好一会儿；不能挂在一个 HTTP 请求上等，会被空闲超时掐断），
 * 进度与结果经 SSE 推给页面（{@code catalog-sync} 事件）。同一时刻只跑一个。
 *
 * <p>启动时若目录还没下过 / 超过 7 天，自动在后台同步一次（{@code agentforge.web.catalog-auto-sync=false} 关掉）。
 */
@Component
public class CatalogSync {
    private static final Logger log = LoggerFactory.getLogger(CatalogSync.class);

    private final AgentRuntime rt;
    private final SessionHub hub;
    private final boolean auto;

    private volatile boolean running;
    private volatile long bytes;
    private volatile String url;
    private volatile Long startedAt;
    private volatile Long finishedAt;
    private volatile String error;
    private volatile Boolean lastOk;
    private volatile long lastPush;

    public CatalogSync(SessionHub hub, @Value("${agentforge.web.catalog-auto-sync:true}") boolean auto) {
        this.hub = hub;
        this.rt = hub.runtime();
        this.auto = auto;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Catalog c = rt.models().catalog();
        if (auto && (c.source() != Catalog.Source.CACHE || c.stale())) {
            log.info("模型目录{}，后台同步一次 models.dev", c.source() == Catalog.Source.CACHE ? "超过 7 天了" : "还没下载过");
            start(null);
        }
    }

    /** 开始同步（已经在跑 ⇒ false）。 */
    public synchronized boolean start(String fromUrl) {
        if (running) return false;
        running = true;
        bytes = 0;
        url = fromUrl == null || fromUrl.isBlank() ? Catalog.URL : fromUrl.strip();
        startedAt = System.currentTimeMillis();
        finishedAt = null;
        error = null;
        push();
        Thread.ofVirtual().name("agentforge-catalog-sync").start(() -> {
            Catalog.SyncResult r;
            try {
                r = rt.syncCatalog(fromUrl, n -> {
                    bytes = n;
                    long now = System.currentTimeMillis();
                    if (now - lastPush > 400) { lastPush = now; push(); }
                });
            } catch (RuntimeException e) {
                r = new Catalog.SyncResult(false, e.getMessage() == null ? e.toString() : e.getMessage(), bytes, 1);
            }
            synchronized (this) {
                running = false;
                lastOk = r.ok();
                error = r.ok() ? null : r.error();
                finishedAt = System.currentTimeMillis();
            }
            if (r.ok()) log.info("模型目录同步完成（{} KB，第 {} 次尝试）", r.bytes() >> 10, r.attempts());
            else log.warn("模型目录同步失败（{} 次尝试）：{}", r.attempts(), r.error());
            push();
            hub.publish(Views.m().put("type", "models").done());
        });
        return true;
    }

    public Map<String, Object> status() {
        return Views.m().put("running", running).put("bytes", bytes).put("url", url).put("startedAt", startedAt)
                .put("finishedAt", finishedAt).put("ok", lastOk).put("error", error).done();
    }

    private void push() {
        hub.publish(Views.m().put("type", "catalog-sync").put("sync", status()).done());
    }
}
