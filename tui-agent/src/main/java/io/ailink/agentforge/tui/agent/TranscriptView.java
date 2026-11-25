package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.transcript.Entry;
import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.Fold;
import io.ailink.agentforge.tui.Overlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一串条目贴进只读的 transcript buffer，<b>只重贴变了的尾巴</b>：
 * 与上一次贴过的条目逐条比（{@code Entry} 是值相等的记录），找到第一条不同的，从它开始往后重排；新旧尾巴的文字再取公共前缀，
 * 只改后面那截 —— 于是流式增量是纯追加（窗口跟尾 / 往上翻时一格不动），一轮落地时形状相同的块原地不动。
 *
 * <p>折叠状态按键（{@code tool:<id>}、{@code res:<id>}、{@code think:<序号>} …）记着，块重贴也不丢。
 * 只在 UI 线程上用。
 */
final class TranscriptView {
    private final Buffer buf;
    private final Blocks.Ctx ctx;
    private final List<Entry> shown = new ArrayList<>();
    private final List<Integer> starts = new ArrayList<>();
    private final List<List<Overlay>> overlays = new ArrayList<>();
    private final Map<String, Boolean> foldState = new HashMap<>();
    private long renders;
    private int width = -1;

    TranscriptView(Buffer buf, Blocks.Ctx ctx) {
        this.buf = buf;
        this.ctx = ctx;
    }

    Buffer buffer() { return buf; }

    /** 贴过的条目数。 */
    int size() { return shown.size(); }

    /** 第几次真的改了 buffer（测试看"没变就不动"）。 */
    long renders() { return renders; }

    /** 全部清掉（换会话）。折叠记忆也清。 */
    void reset() {
        for (List<Overlay> os : overlays) for (Overlay o : os) o.delete();
        shown.clear();
        starts.clear();
        overlays.clear();
        foldState.clear();
        buf.clear();
    }

    /** 让 buffer 显示 desired（只改变了的尾巴）。⇒ 改了没有。 */
    boolean update(List<Entry> desired) {
        int n = Math.min(shown.size(), desired.size());
        int i = 0;
        int w = ctx.width();
        // 宽度变了：表格、分隔线这些按宽度排的都要重排 ⇒ 整份重排（文字仍取公共前缀，buffer 只改变了的那截）
        if (w == width) while (i < n && shown.get(i).equals(desired.get(i))) i++;
        width = w;
        if (i == shown.size() && i == desired.size()) return false;
        renders++;
        // 记下要重贴那几块的折叠状态，再摘掉它们的覆盖层
        for (int j = i; j < overlays.size(); j++) {
            for (Overlay o : overlays.get(j)) {
                // 只记用户自己开合过的（和排版给的默认不一样）：默认变了（子 agent 跑完该折起来）就照新的默认
                if (Fold.isFold(o) && o.get("fold-key") instanceof String k) {
                    if (!Boolean.valueOf(o.invisible()).equals(o.get("fold-default"))) foldState.put(k, o.invisible());
                    else foldState.remove(k);
                }
                o.delete();
            }
        }
        int from = i < starts.size() ? starts.get(i) : buf.length();
        while (shown.size() > i) {
            shown.removeLast();
            starts.removeLast();
            overlays.removeLast();
        }
        // 排新的尾巴
        StringBuilder tail = new StringBuilder();
        List<Chunk> chunks = new ArrayList<>();
        int userNo = 0;
        for (int j = 0; j < i; j++) if (desired.get(j).role() == Entry.Role.USER && Blocks.Live.kind(desired.get(j)) == null) userNo++;
        List<Integer> newStarts = new ArrayList<>();
        for (int j = i; j < desired.size(); j++) {
            Entry e = desired.get(j);
            if (e.role() == Entry.Role.USER && Blocks.Live.kind(e) == null) userNo++;
            Entry prev = j > 0 ? desired.get(j - 1) : null;
            Chunk c = Blocks.render(e, prev, userNo, j, ctx);
            newStarts.add(from + tail.length());
            tail.append(c.sb);
            chunks.add(c);
        }
        String oldTail = buf.substring(from, buf.length());
        String nt = tail.toString();
        int cp = 0, lim = Math.min(oldTail.length(), nt.length());
        while (cp < lim && oldTail.charAt(cp) == nt.charAt(cp)) cp++;
        if (cp < oldTail.length() || cp < nt.length()) buf.replace(from + cp, buf.length(), nt.substring(cp));
        // 覆盖层
        for (int k = 0; k < chunks.size(); k++) {
            Chunk c = chunks.get(k);
            int base = newStarts.get(k);
            List<Overlay> os = new ArrayList<>();
            int len = c.sb.length();
            for (Chunk.Span s : c.spans) {
                Overlay o = buf.addOverlay(base + s.a(), base + s.b()).face(s.face());
                if (s.extend()) o.extend(true);
                os.add(o);
            }
            for (Chunk.Prefix p : c.prefixes) {
                Overlay o = buf.addOverlay(base + p.a(), base + p.b()).wrapPrefix(p.segs());
                o.priority(-2);
                os.add(o);
            }
            for (Chunk.Act a : c.acts) {
                Overlay o = buf.addOverlay(base + a.a(), base + a.b()).action(a.action());
                o.face(null);
                o.priority(-1);
                os.add(o);
            }
            for (Chunk.FoldSpec f : c.folds) {
                boolean folded = foldState.getOrDefault(f.key(), f.folded());
                Overlay o = f.segs() != null ? Fold.create(buf, base + f.a(), base + f.b(), f.segs(), folded, f.plain() ? null : "fold-summary")
                        : Fold.create(buf, base + f.a(), base + f.b(), f.summary(), folded);
                o.put("fold-key", f.key());
                o.put("fold-default", f.folded());
                os.add(o);
            }
            if (c.blockId != null && len > 0) {
                int a = base;
                // 块从第一个非换行的字开始（前面那行空白不算进块）
                while (a < base + len && buf.charAt(a) == '\n') a++;
                Overlay b = Fold.markBlock(buf, a, base + len, c.blockId);
                b.put("raw", c.raw != null ? c.raw : buf.substring(a, base + len).stripTrailing());
                os.add(b);
            }
            shown.add(desired.get(i + k));
            starts.add(base);
            overlays.add(os);
        }
        return true;
    }

    /** pos 所在块的原文（M-w）；不在块里 ⇒ null。 */
    String rawAt(int pos) {
        Overlay b = Fold.blockAt(buf, pos);
        if (b == null && pos > 0) b = Fold.blockAt(buf, pos - 1);
        return b == null ? null : (String) b.get("raw");
    }

    /** pos 所在块的种类（"user" / "assistant" / "group" …）；没有 ⇒ null。 */
    Object blockKindAt(int pos) {
        Overlay b = Fold.blockAt(buf, pos);
        return b == null ? null : b.get(Fold.BLOCK);
    }

    /** 最后一块回答的原文（输入框里 M-w 没有选区时拷它）。 */
    String lastAssistantRaw() {
        for (int k = shown.size() - 1; k >= 0; k--) {
            Entry e = shown.get(k);
            if (e.role() == Entry.Role.ASSISTANT && !e.flag("thinking") && !e.text().isBlank()) return e.text();
        }
        return null;
    }
}
