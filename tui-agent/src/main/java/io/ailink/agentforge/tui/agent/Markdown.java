package io.ailink.agentforge.tui.agent;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlCommentBlock;
import com.vladsch.flexmark.ast.HtmlEntity;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.HtmlInlineComment;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.LinkRef;
import com.vladsch.flexmark.ast.ListBlock;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListItem;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TableSeparator;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Seg;
import io.ailink.agentforge.tui.text.Width;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * markdown ⇒ 终端排版（写进 {@link Chunk}）。解析交给 flexmark（CommonMark + GFM 表格 / 删除线 / 任务清单 / 裸链接），
 * 这里只把 AST 画成带 face 的行：
 * <ul>
 *   <li>标题按级别换色（一级下面一道粗线），粗体 / 斜体 / 删除线 / 行内代码 / 链接（文字 + 暗色网址，RET / 单击打开）；</li>
 *   <li>代码块整块衬底（铺到行尾）、头一行写语言，按 {@link Highlight} 着色；</li>
 *   <li>列表（• ◦ ▪ / 1.）、任务清单（☐ ☑）、引用（│）都是「前缀栈」：首行画记号，续行（换行、软折行）画同宽的空白 ——
 *       软折行靠覆盖层的续行前缀（{@code wrap-prefix}），所以窗口变窄不用重排也能悬挂缩进；</li>
 *   <li>表格按显示宽度对齐（中文算两格）、画框线；放不下先把宽列折成多行，再不行退成逐行「表头: 值」；</li>
 *   <li>分隔线、HTML 标签去掉只留文字。</li>
 * </ul>
 * 流式时整段重解析重排（flexmark 对没收口的代码块按「代码块直到末尾」处理），由 {@link TranscriptView} 只改变了的尾巴。
 * 表格、分隔线按宽度排：宽度变了由 TranscriptView 整份重排。纯函数，不碰编辑器状态。
 */
final class Markdown {
    private Markdown() { }

    private static final Parser PARSER;

    static {
        MutableDataSet o = new MutableDataSet();
        o.set(Parser.EXTENSIONS, List.of(TablesExtension.create(), StrikethroughExtension.create(),
                TaskListExtension.create(), AutolinkExtension.create()));
        o.set(TablesExtension.COLUMN_SPANS, false);
        o.set(TablesExtension.APPEND_MISSING_COLUMNS, true);
        o.set(TablesExtension.DISCARD_EXTRA_COLUMNS, true);
        o.set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, false);
        PARSER = Parser.builder(o).build();
    }

    static final String[] BULLETS = {"•", "◦", "▪"};

    /** 解析（测试也用）。 */
    static Document parse(String md) { return PARSER.parse(md == null ? "" : md); }

    /**
     * 把 md 排进 c。width = 记录区正文宽；margin = 每行左边先垫的串（续行也垫）；open = 点链接时调（可 null）。
     */
    static void render(Chunk c, String md, int width, String margin, BiConsumer<Editor, String> open) {
        render(c, md, width, margin, margin, null, open);
    }

    /** 同上，但第一行先垫 first（face = firstFace，Codex 回答前那个 {@code • }），之后的行垫 rest。 */
    static void render(Chunk c, String md, int width, String first, String rest, String firstFace, BiConsumer<Editor, String> open) {
        Writer w = new Writer(c, Math.max(20, width), open);
        if (first != null && !first.isEmpty()) w.push(first, rest, firstFace);
        Document doc = parse(md.replace("\r\n", "\n").replace('\r', '\n'));
        w.doc = doc;
        w.blocks(doc, false);
        if (!w.atBol) w.end();
    }

    // ══ 排版器 ═══════════════════════════════════════════════════════════

    /** 一层前缀：首行画 first，之后画 rest。 */
    private static final class Pfx {
        final String first, rest, face;
        boolean used;

        Pfx(String first, String rest, String face) { this.first = first; this.rest = rest; this.face = face; }
    }

    /** 一段行内文字：字、叠着的 face、链接（可 null）。 */
    record Run(String text, List<String> faces, String url) { }

    private static final class Writer {
        final Chunk c;
        final int width;
        final BiConsumer<Editor, String> open;
        final Deque<Pfx> stack = new ArrayDeque<>();
        final List<String> base = new ArrayList<>();
        Document doc;
        boolean atBol = true;
        boolean lastBlank = true;      // 开头算「刚空过一行」：块前不垫空行
        int lineA;

        Writer(Chunk c, int width, BiConsumer<Editor, String> open) {
            this.c = c;
            this.width = width;
            this.open = open;
        }

        void push(String first, String rest, String face) { stack.addLast(new Pfx(first, rest, face)); }

        void pop() { stack.removeLast(); }

        /** 去掉前缀后还有几列。 */
        int avail() {
            int w = 0;
            for (Pfx p : stack) w += Width.stringWidth(p.rest);
            return Math.max(8, width - w);
        }

        /** 行开头：画各层前缀。 */
        void begin() {
            if (!atBol) return;
            lineA = c.pos();
            for (Pfx p : stack) {
                c.add(p.used ? p.rest : p.first, p.face);
                p.used = true;
            }
            atBol = false;
        }

        List<Seg> cont(Seg extra) {
            List<Seg> out = new ArrayList<>();
            for (Pfx p : stack) if (!p.rest.isEmpty()) out.add(Seg.of(p.rest, p.face));
            if (extra != null) out.add(extra);
            return out;
        }

        /** 收一行（续行前缀 = 各层的 rest + extra）。 */
        void end(Seg extra) {
            begin();
            c.prefix(lineA, c.pos(), cont(extra));
            c.add("\n");
            atBol = true;
            lastBlank = false;
        }

        void end() { end(null); }

        /** 块与块之间空一行（引用里的空行留着竖条）。 */
        void blank() {
            if (!atBol) end();
            if (lastBlank) return;
            for (Pfx p : stack) {
                c.add((p.used ? p.rest : p.first).stripTrailing(), p.face);
            }
            c.add("\n");
            lastBlank = true;
        }

        void run(String text, List<String> faces, String url) {
            if (text.isEmpty()) return;
            String[] parts = text.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) end();
                if (parts[i].isEmpty()) continue;
                begin();
                int a = c.pos();
                c.add(parts[i]);
                int b = c.pos();
                for (String f : faces) c.face(a, b, f);
                if (url != null && open != null) {
                    String u = url;
                    c.act(a, b, ed -> open.accept(ed, u));
                }
            }
        }

        // ── 块 ──────────────────────────────────────────────────────────

        void blocks(Node parent, boolean tight) {
            boolean first = true;
            for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof Reference || n instanceof HtmlCommentBlock) continue;
                if (!first && !tight) blank();
                if (!first && tight && !atBol) end();
                block(n, tight);
                first = false;
            }
        }

        void block(Node n, boolean tight) {
            switch (n) {
                case Heading h -> heading(h);
                case Paragraph p -> paragraph(p);
                case ListBlock l -> list(l);
                case BlockQuote q -> {
                    push("│ ", "│ ", "markdown-quote-bar");
                    base.add("markdown-blockquote-face");
                    blocks(q, false);
                    if (!atBol) end();
                    base.removeLast();
                    pop();
                }
                case FencedCodeBlock f -> code(f.getInfo().toString(), f.getContentChars().toString());
                case IndentedCodeBlock ic -> code("", ic.getContentChars().toString());
                case ThematicBreak t -> {
                    begin();
                    c.add("─".repeat(avail()), "markdown-hr-face");
                    end();
                }
                case TableBlock t -> table(t);
                case HtmlBlock hb -> {
                    String s = stripTags(hb.getChars().toString()).strip();
                    if (!s.isEmpty()) {
                        List<String> fs = new ArrayList<>(base);
                        for (String line : s.split("\n")) { run(line.strip(), fs, null); end(); }
                    }
                }
                default -> {
                    if (n.getFirstChild() != null && !(n.getFirstChild() instanceof Text)) blocks(n, tight);
                    else {
                        inline(n, new ArrayList<>(base), null);
                        if (!atBol) end();
                    }
                }
            }
        }

        void heading(Heading h) {
            int lv = Math.max(1, Math.min(4, h.getLevel()));
            List<String> fs = new ArrayList<>(base);
            fs.add("markdown-header-face-" + lv);
            begin();
            int a = c.pos();
            inline(h, fs, null);
            int tw = Width.stringWidth(c.sb.substring(a, c.pos()));
            if (!atBol) end();
            if (lv == 1 && tw > 0) {
                begin();
                c.add("━".repeat(Math.min(avail(), tw)), "markdown-header-rule");
                end();
            }
        }

        void paragraph(Paragraph p) {
            inline(p, new ArrayList<>(base), null);
            if (!atBol) end();
        }

        void list(ListBlock l) {
            int depth = 0;
            for (Node x = l.getParent(); x != null; x = x.getParent()) if (x instanceof ListBlock) depth++;
            boolean ordered = l instanceof OrderedList;
            int start = l instanceof OrderedList o ? o.getStartNumber() : 1;
            char delim = l instanceof OrderedList o ? o.getDelimiter() : '.';
            int count = 0;
            for (Node it = l.getFirstChild(); it != null; it = it.getNext()) count++;
            int numW = String.valueOf(start + Math.max(0, count - 1)).length() + 1;
            boolean tight = !l.isLoose();
            int k = 0;
            for (Node it = l.getFirstChild(); it != null; it = it.getNext(), k++) {
                if (k > 0 && !tight) blank();
                else if (!atBol) end();
                String marker, face;
                if (ordered) {
                    String num = (start + k) + String.valueOf(delim);
                    marker = " ".repeat(Math.max(0, numW - num.length())) + num + " ";
                    face = "markdown-list-face";
                } else {
                    marker = BULLETS[depth % BULLETS.length] + " ";
                    face = "markdown-list-face";
                }
                if (it instanceof TaskListItem t) {
                    boolean done = t.isItemDoneMarker();
                    String box = done ? "☑ " : "☐ ";
                    marker = ordered ? marker + box : box;
                    face = done ? "markdown-task-done" : "markdown-task-todo";
                }
                push(marker, " ".repeat(Width.stringWidth(marker)), face);
                if (it.getFirstChild() == null) { begin(); end(); }
                else blocks(it, tight);
                if (!atBol) end();
                pop();
            }
        }

        void code(String info, String body) {
            String lang = info == null ? "" : info.strip().split("\\s+", 2)[0];
            List<String> ls = new ArrayList<>();
            String b = body.endsWith("\n") ? body.substring(0, body.length() - 1) : body;
            if (!b.isEmpty() || body.endsWith("\n")) for (String l : b.split("\n", -1)) ls.add(l.replace("\t", "    "));
            if (!atBol) end();
            // 头一行：语言名（没写就空一行衬底）
            begin();
            int a = c.pos();
            c.add(lang.isEmpty() ? " " : " " + lang, "markdown-code-lang");
            end();
            c.ext(a, c.pos(), "markdown-code-block");
            List<List<Highlight.Span>> hs = Highlight.spans(lang, ls);
            for (int i = 0; i < ls.size(); i++) {
                begin();
                int la = c.pos();
                c.add("  ");
                int t = c.pos();
                c.add(ls.get(i));
                for (Highlight.Span s : hs.get(i)) c.face(t + s.a(), t + s.b(), s.face());
                end(Seg.of("  ", "markdown-code-block"));
                c.ext(la, c.pos(), "markdown-code-block");
            }
        }

        // ── 表格 ────────────────────────────────────────────────────────

        /** 一格：拍平的字 + 每个字属于哪段 run。 */
        private static final class Cell {
            final List<Run> runs = new ArrayList<>();
            String text = "";
            int[] runOf = new int[0];

            void seal() {
                StringBuilder sb = new StringBuilder();
                List<Integer> idx = new ArrayList<>();
                for (int r = 0; r < runs.size(); r++) {
                    String t = runs.get(r).text().replace('\n', ' ');
                    sb.append(t);
                    for (int i = 0; i < t.length(); i++) idx.add(r);
                }
                text = sb.toString();
                runOf = idx.stream().mapToInt(Integer::intValue).toArray();
            }

            int width() { return Width.stringWidth(text); }
        }

        void table(TableBlock t) {
            List<List<Cell>> rows = new ArrayList<>();
            List<TableCell.Alignment> aligns = new ArrayList<>();
            int headRows = 0;
            for (Node sec = t.getFirstChild(); sec != null; sec = sec.getNext()) {
                if (sec instanceof TableSeparator) continue;
                boolean head = sec instanceof TableHead;
                for (Node r = sec.getFirstChild(); r != null; r = r.getNext()) {
                    if (!(r instanceof TableRow)) continue;
                    List<Cell> row = new ArrayList<>();
                    int j = 0;
                    for (Node cn = r.getFirstChild(); cn != null; cn = cn.getNext(), j++) {
                        if (!(cn instanceof TableCell tc)) continue;
                        Cell cell = new Cell();
                        collect(tc, head ? List.of("markdown-table-header") : List.of(), null, cell.runs);
                        cell.seal();
                        row.add(cell);
                        while (aligns.size() <= j) aligns.add(null);
                        if (tc.getAlignment() != null && aligns.get(j) == null) aligns.set(j, tc.getAlignment());
                    }
                    rows.add(row);
                    if (head) headRows++;
                }
            }
            int n = 0;
            for (List<Cell> r : rows) n = Math.max(n, r.size());
            if (n == 0) return;
            for (List<Cell> r : rows) while (r.size() < n) { Cell e = new Cell(); e.seal(); r.add(e); }
            while (aligns.size() < n) aligns.add(null);
            int[] nat = new int[n];
            for (List<Cell> r : rows) for (int j = 0; j < n; j++) nat[j] = Math.max(nat[j], Math.max(1, r.get(j).width()));
            int avail = avail();
            int frame = 3 * n + 1;
            int[] cw = nat.clone();
            int sum = 0;
            for (int x : cw) sum += x;
            // 放不下：一格一格削最宽的那列（每列至少 min(自然宽, 6)）
            while (sum + frame > avail) {
                int wi = -1;
                for (int j = 0; j < n; j++) if (cw[j] > Math.min(nat[j], 6) && (wi < 0 || cw[j] > cw[wi])) wi = j;
                if (wi < 0) break;
                cw[wi]--;
                sum--;
            }
            if (!atBol) end();
            if (sum + frame > avail) { records(rows, headRows); return; }
            String bf = "markdown-table-border";
            border("┌", "┬", "┐", cw, bf);
            boolean multi = false;
            List<List<List<List<Run>>>> wrapped = new ArrayList<>();
            for (List<Cell> r : rows) {
                List<List<List<Run>>> wr = new ArrayList<>();
                for (int j = 0; j < n; j++) {
                    List<List<Run>> lines = wrap(r.get(j), cw[j]);
                    if (lines.size() > 1) multi = true;
                    wr.add(lines);
                }
                wrapped.add(wr);
            }
            for (int ri = 0; ri < rows.size(); ri++) {
                List<List<List<Run>>> wr = wrapped.get(ri);
                int h = 1;
                for (List<List<Run>> x : wr) h = Math.max(h, x.size());
                for (int li = 0; li < h; li++) {
                    begin();
                    c.add("│", bf);
                    for (int j = 0; j < n; j++) {
                        List<Run> line = li < wr.get(j).size() ? wr.get(j).get(li) : List.of();
                        int lw = 0;
                        for (Run x : line) lw += Width.stringWidth(x.text());
                        int padTotal = Math.max(0, cw[j] - lw);
                        TableCell.Alignment al = aligns.get(j);
                        int left = al == TableCell.Alignment.RIGHT ? padTotal : al == TableCell.Alignment.CENTER ? padTotal / 2 : 0;
                        c.add(" " + " ".repeat(left));
                        for (Run x : line) runAt(x);
                        c.add(" ".repeat(padTotal - left) + " ");
                        c.add("│", bf);
                    }
                    end();
                }
                boolean lastHead = ri == headRows - 1;
                if (lastHead && ri < rows.size() - 1) border("├", "┼", "┤", cw, bf);
                else if (multi && ri < rows.size() - 1 && ri >= headRows) border("├", "┼", "┤", cw, bf);
            }
            border("└", "┴", "┘", cw, bf);
        }

        /** 不经 begin 的一段（表格行里已经开过行了）。 */
        void runAt(Run x) {
            int a = c.pos();
            c.add(x.text());
            int b = c.pos();
            for (String f : x.faces()) c.face(a, b, f);
            if (x.url() != null && open != null) {
                String u = x.url();
                c.act(a, b, ed -> open.accept(ed, u));
            }
        }

        void border(String l, String m, String r, int[] cw, String face) {
            begin();
            StringBuilder sb = new StringBuilder(l);
            for (int j = 0; j < cw.length; j++) {
                sb.append("─".repeat(cw[j] + 2));
                sb.append(j == cw.length - 1 ? r : m);
            }
            c.add(sb.toString(), face);
            end();
        }

        /** 太窄画不下表格：每行一组「表头: 值」。 */
        void records(List<List<Cell>> rows, int headRows) {
            List<Cell> head = headRows > 0 ? rows.getFirst() : null;
            for (int ri = headRows; ri < rows.size(); ri++) {
                if (ri > headRows) blank();
                List<Cell> r = rows.get(ri);
                for (int j = 0; j < r.size(); j++) {
                    push(j == 0 ? "▪ " : "  ", "  ", "markdown-list-face");
                    begin();
                    if (head != null && !head.get(j).text.isBlank()) {
                        c.add(head.get(j).text, "markdown-table-header");
                        c.add(": ", "markdown-table-border");
                    }
                    for (Run x : r.get(j).runs) run(x.text().replace('\n', ' '), x.faces(), x.url());
                    end();
                    pop();
                }
            }
        }

        /** 一格按显示宽度折成几行（优先断在空白后；中文哪儿都能断）。 */
        static List<List<Run>> wrap(Cell cell, int w) {
            List<List<Run>> out = new ArrayList<>();
            String s = cell.text;
            if (s.isEmpty()) { out.add(List.of()); return out; }
            int i = 0;
            while (i < s.length()) {
                int col = 0, j = i, lastSp = -1;
                while (j < s.length()) {
                    int cp = s.codePointAt(j);
                    int cw = Width.cpWidth(cp);
                    if (col + cw > w && j > i) break;
                    col += cw;
                    j += Character.charCount(cp);
                    if (cp == ' ') lastSp = j;
                }
                int cut = j;
                if (j < s.length() && lastSp > i && s.charAt(j) != ' ') cut = lastSp;
                out.add(slice(cell, i, cut));
                i = cut;
                while (i < s.length() && s.charAt(i) == ' ' && out.size() > 0) i++;
            }
            return out;
        }

        static List<Run> slice(Cell cell, int a, int b) {
            List<Run> out = new ArrayList<>();
            String s = cell.text;
            while (b > a && s.charAt(b - 1) == ' ') b--;
            int k = a;
            while (k < b) {
                int r = cell.runOf[k];
                int e = k;
                while (e < b && cell.runOf[e] == r) e++;
                Run src = cell.runs.get(r);
                out.add(new Run(s.substring(k, e), src.faces(), src.url()));
                k = e;
            }
            return out;
        }

        // ── 行内 ────────────────────────────────────────────────────────

        /** 行内节点 ⇒ 直接写进 chunk。 */
        void inline(Node parent, List<String> faces, String url) {
            List<Run> runs = new ArrayList<>();
            collect(parent, faces, url, runs);
            for (Run r : runs) {
                if (r == BREAK) end();
                else run(r.text(), r.faces(), r.url());
            }
        }

        static final Run BREAK = new Run("\n", List.of(), null);

        /** 行内节点 ⇒ 一串 run（换行记成 BREAK）。 */
        void collect(Node parent, List<String> faces, String url, List<Run> out) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
                switch (n) {
                    case Text t -> out.add(new Run(t.getChars().unescape(), faces, url));
                    case Code k -> out.add(new Run(k.getText().toString().replace('\n', ' '), with(faces, "markdown-inline-code"), url));
                    case StrongEmphasis s -> collect(s, with(faces, "markdown-bold"), url, out);
                    case Emphasis e -> collect(e, with(faces, "markdown-italic"), url, out);
                    case Strikethrough s -> collect(s, with(faces, "markdown-strike"), url, out);
                    case Link l -> link(l, l.getUrl().unescape(), faces, out);
                    case LinkRef r -> {
                        Reference ref = doc == null ? null : r.getReferenceNode(doc);
                        if (ref != null) link(r, ref.getUrl().unescape(), faces, out);
                        else out.add(new Run(r.getChars().unescape(), faces, url));
                    }
                    case AutoLink a -> out.add(new Run(a.getText().unescape(), with(faces, "markdown-link"), a.getUrl().unescape()));
                    case MailLink m -> out.add(new Run(m.getText().unescape(), with(faces, "markdown-link"), "mailto:" + m.getText().unescape()));
                    case Image im -> out.add(new Run("⧉ " + alt(im), with(faces, "markdown-image"), im.getUrl().unescape()));
                    case ImageRef im -> out.add(new Run("⧉ " + alt(im), with(faces, "markdown-image"), url));
                    case SoftLineBreak sb -> out.add(BREAK);
                    case HardLineBreak hb -> out.add(BREAK);
                    case HtmlInline h -> { }
                    case HtmlInlineComment h -> { }
                    case HtmlEntity h -> out.add(new Run(h.getChars().unescape(), faces, url));
                    default -> {
                        if (n.getFirstChild() != null) collect(n, faces, url, out);
                        else out.add(new Run(n.getChars().unescape(), faces, url));
                    }
                }
            }
        }

        void link(Node l, String href, List<String> faces, List<Run> out) {
            int before = out.size();
            collect(l, with(faces, "markdown-link"), href, out);
            StringBuilder txt = new StringBuilder();
            for (int i = before; i < out.size(); i++) txt.append(out.get(i).text());
            if (txt.isEmpty()) out.add(new Run(href, with(faces, "markdown-link"), href));
            else if (!txt.toString().equals(href) && !href.isEmpty() && !href.startsWith("#"))
                out.add(new Run(" (" + href + ")", List.of("markdown-link-url"), href));
        }

        static String alt(Node im) {
            StringBuilder sb = new StringBuilder();
            for (Node n = im.getFirstChild(); n != null; n = n.getNext()) sb.append(n.getChars().unescape());
            return sb.isEmpty() ? Msg.t("image") : sb.toString();
        }

        static List<String> with(List<String> faces, String f) {
            List<String> out = new ArrayList<>(faces);
            out.add(f);
            return out;
        }
    }

    static String stripTags(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "").replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
