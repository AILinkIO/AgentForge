package io.ailink.agentforge.web;

import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.session.SessionEvent;
import io.ailink.agentforge.agent.session.SessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 会话 ↔ 浏览器：给每段会话挂监听，把事件排进一条队列，由一条虚拟线程按顺序推给所有 SSE 连接。
 *
 * <p>⚠️ 会话回调跑在 agent 的工作线程上，不能在那里直接写网络（慢客户端会拖住 agent）：只入队。
 * 队列满了就丢流式增量（前端收到下一个非增量事件会重取快照，丢掉的字补得回来），别的事件不丢。
 */
@Component
public class SessionHub implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(SessionHub.class);
    private static final int QUEUE_CAP = 10_000;
    private static final long HEARTBEAT_MS = 15_000;

    private final AgentRuntime rt;
    private final WebProperties props;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Set<String> attached = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(QUEUE_CAP);
    private final SessionListener listener = this::onEvent;
    private final Thread pump;
    private volatile boolean closed;

    public SessionHub(AgentRuntime rt, WebProperties props) {
        this.rt = rt;
        this.props = props;
        this.pump = Thread.ofVirtual().name("agentforge-sse").start(this::pumpLoop);
    }

    // ── 会话 ───────────────────────────────────────────────────────────

    public AgentRuntime runtime() { return rt; }

    /** 主会话（不含子 agent），先建的在前。 */
    public List<AgentSession> sessions() {
        List<AgentSession> out = new ArrayList<>();
        for (AgentSession s : rt.sessions()) if (s.parent() == null) { attach(s); out.add(s); }
        return out;
    }

    public AgentSession create(String agent) {
        AgentSession s = rt.newSession(agent != null && !agent.isBlank() ? agent : props.agent());
        attach(s);
        sessionsChanged();
        return s;
    }

    /** 开在项目里的新会话。 */
    public AgentSession create(String agent, io.ailink.agentforge.agent.project.Project project, String folder) {
        AgentSession s = rt.newSession(agent != null && !agent.isBlank() ? agent : props.agent(), project, folder);
        attach(s);
        sessionsChanged();
        return s;
    }

    /** 打开存着的一段对话（已经开着就直接回那一段）。 */
    public AgentSession open(long conv) {
        for (AgentSession s : rt.sessions()) {
            Long c = rt.conversationOf(s);
            if (c != null && c == conv) { attach(s); return s; }
        }
        AgentSession s = rt.openSession(conv);
        attach(s);
        sessionsChanged();
        return s;
    }

    /** 按 id 找会话（含各层子 agent）；找到就挂上监听。找不到 ⇒ null。 */
    public AgentSession find(String id) {
        for (AgentSession s : rt.sessions()) {
            AgentSession hit = find(s, id);
            if (hit != null) { attach(hit); return hit; }
        }
        return null;
    }

    private static AgentSession find(AgentSession s, String id) {
        if (s.id().equals(id)) return s;
        for (AgentSession c : s.children()) {
            AgentSession hit = find(c, id);
            if (hit != null) return hit;
        }
        return null;
    }

    public void close(AgentSession s) {
        if (s.busy()) s.interrupt();
        s.removeListener(listener);
        attached.remove(s.id());
        rt.save(s);
        rt.closeSession(s);
        sessionsChanged();
    }

    void attach(AgentSession s) {
        if (attached.add(s.id())) s.addListener(listener);
    }

    /** 宿主自己的事件（不是会话发的，比如异步压缩失败）。 */
    public void publish(Map<String, Object> event) {
        enqueue(event, true);
    }

    /** 会话表变了（新建 / 关掉 / 标题变了）：前端重取侧栏。 */
    public void sessionsChanged() {
        enqueue(Views.m().put("type", "sessions").done(), true);
    }

    private void onEvent(AgentSession s, SessionEvent ev) {
        Map<String, Object> m;
        try {
            m = Views.event(s, ev);
        } catch (RuntimeException e) {
            log.warn("事件投影失败：{}", ev.getClass().getSimpleName(), e);
            return;
        }
        enqueue(m, !(ev instanceof SessionEvent.Delta));
        // 子 agent 起来了：把它也挂上，前端点进去能看活的
        if (ev instanceof SessionEvent.SubStart) for (AgentSession c : s.children()) attach(c);
        // 一轮落地 / 第一句话进来：侧栏的标题、状态、计数跟着变
        if (ev instanceof SessionEvent.Started || ev instanceof SessionEvent.Finished || ev instanceof SessionEvent.Error
                || ev instanceof SessionEvent.Stopped) sessionsChanged();
    }

    // ── SSE ────────────────────────────────────────────────────────────

    public SseEmitter subscribe() {
        SseEmitter em = new SseEmitter(0L);       // 不超时：靠心跳发现断开
        em.onCompletion(() -> emitters.remove(em));
        em.onTimeout(() -> emitters.remove(em));
        em.onError(e -> emitters.remove(em));
        emitters.add(em);
        try {
            em.send(SseEmitter.event().name("hello").data(Views.m().put("type", "hello").done(), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitters.remove(em);
        }
        return em;
    }

    int subscribers() { return emitters.size(); }

    private void enqueue(Map<String, Object> m, boolean mustKeep) {
        if (closed) return;
        if (queue.offer(m)) return;
        if (!mustKeep) return;
        try {
            queue.offer(m, 1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pumpLoop() {
        long lastBeat = System.currentTimeMillis();
        while (!closed) {
            Map<String, Object> m;
            try {
                m = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (m != null) {
                for (SseEmitter em : emitters) {
                    try {
                        em.send(SseEmitter.event().name("agent").data(m, MediaType.APPLICATION_JSON));
                    } catch (IOException | IllegalStateException e) {
                        drop(em);
                    }
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                for (SseEmitter em : emitters) {
                    try {
                        em.send(SseEmitter.event().comment("ping"));
                    } catch (IOException | IllegalStateException e) {
                        drop(em);
                    }
                }
            }
        }
    }

    private void drop(SseEmitter em) {
        emitters.remove(em);
        try { em.complete(); } catch (RuntimeException ignored) { }
    }

    @Override
    public void destroy() {
        closed = true;
        pump.interrupt();
        for (SseEmitter em : emitters) drop(em);
        for (AgentSession s : rt.sessions()) {
            try { rt.save(s); } catch (RuntimeException e) { log.warn("存会话失败：{}", s.id(), e); }
        }
    }
}
