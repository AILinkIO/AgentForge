package io.ailink.agentforge.agent.tools.file;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.Check;
import io.ailink.agentforge.agent.tools.FileText;
import io.ailink.agentforge.agent.tools.Limit;
import io.ailink.agentforge.agent.tools.Seen;
import io.ailink.agentforge.agent.tools.Tags;
import io.ailink.agentforge.agent.tools.ToolText;
import io.ailink.agentforge.agent.tools.Vfs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.ailink.agentforge.agent.tools.ToolText.q;

/**
 * read-file / list-dir / write-file（对应 coxswain {@code tools/file.ss}）。
 *
 * <p>碰盘一律走 {@link Vfs}；新鲜度一律问 {@link Seen}；写之前过 {@link Check}。
 */
public final class FileTools {
    private FileTools() { }

    /** 当前 provider 的工具结果吃不吃 document 块（Anthropic 吃，OpenAI 的 tool 消息没有）。会话以 ToolEnv service 注入；没注入按"吃"。 */
    @FunctionalInterface
    public interface DocumentSupport { boolean supported(); }

    public static final int FILE_LIMIT = 60000;
    public static final int ENTRY_LIMIT = 300;
    public static final int LINE_CHAR_LIMIT = 2000;
    /** 单张图直读上限：原始字节 3.75MB，编出来约 5MB 正是 Anthropic 单图上限（与 attach 同值）。 */
    public static final long MEDIA_IMAGE_MAX_BYTES = 3_750_000L;
    public static final long MEDIA_PDF_MAX_BYTES = 20_971_520L;

    public static List<AgentTool> tools() { return List.of(readFile(), listDir(), writeFile()); }

    // ── 编号 ──────────────────────────────────────────────────────────

    public static String clipLine(String s) {
        if (s.length() <= LINE_CHAR_LIMIT) return s;
        return s.substring(0, LINE_CHAR_LIMIT) + "…（本行已截断至 " + LINE_CHAR_LIMIT + " 字符）";
    }

    /** 编号结果：正文、真印出来的最后一个行号、有没有撞上字节预算。 */
    record Numbered(String body, int last, boolean cut) { }

    /**
     * 编号 + 分页窗口 + 字符预算。预算按整行切（不在一行中间砍），撞了预算就等于窗口在那里结束，
     * 尾注给的 offset 一定指向下一个没印的行。至少印一行（空正文加"用 offset=1 继续"是死循环）。
     */
    static Numbered numbered(String text, int offset, int limit, int budget) {
        int n = text.length();
        StringBuilder out = new StringBuilder();
        long past = limit == 0 ? -1 : (long) offset + limit;
        int start = 0, ln = 1, last = offset - 1, used = 0;
        for (int i = 0; i <= n; i++) {
            boolean end = i == n;
            if (!end && text.charAt(i) != '\n') continue;
            if (end && i <= start) break;                 // 以换行结尾就不再补一个空行
            boolean inWin = ln >= offset && (past == -1 || ln < past);
            if (inWin) {
                String line = ln + ":" + clipLine(text.substring(start, i)) + (end ? "" : "\n");
                if (used > 0 && used + line.length() > budget) return new Numbered(out.toString(), last, true);
                out.append(line);
                used += line.length();
                last = ln;
            }
            ln++;
            start = i + 1;
        }
        return new Numbered(out.toString(), last, false);
    }

    // ── 魔数嗅探 ──────────────────────────────────────────────────────

    public enum Mime { PNG, JPEG, GIF, WEBP, BMP, PDF, UTF8_BOM }

    private static boolean starts(byte[] bv, int i, int... bytes) {
        for (int k = 0; k < bytes.length; k++) {
            if (i + k >= bv.length || (bv[i + k] & 0xFF) != bytes[k]) return false;
        }
        return true;
    }

    /** 按文件开头的魔数认类型；认不出回 null。JPEG 只认前三字节（第四个是段标记）；WebP 要 RIFF + WEBP 两截。 */
    public static Mime sniffMime(byte[] bv) {
        if (bv == null) return null;
        if (starts(bv, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return Mime.PNG;
        if (starts(bv, 0, 0xFF, 0xD8, 0xFF)) return Mime.JPEG;
        if (starts(bv, 0, 0x47, 0x49, 0x46, 0x38)) return Mime.GIF;
        if (starts(bv, 0, 0x52, 0x49, 0x46, 0x46) && starts(bv, 8, 0x57, 0x45, 0x42, 0x50)) return Mime.WEBP;
        if (starts(bv, 0, 0x42, 0x4D)) return Mime.BMP;
        if (starts(bv, 0, 0x25, 0x50, 0x44, 0x46, 0x2D)) return Mime.PDF;
        if (starts(bv, 0, 0xEF, 0xBB, 0xBF)) return Mime.UTF8_BOM;
        return null;
    }

    private static Mime sniffFile(String full) {
        if (Vfs.isBufferAddress(full)) return null;
        try (InputStream in = Files.newInputStream(Path.of(full))) {
            return sniffMime(in.readNBytes(12));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String mediaMime(Mime m) {
        if (m == null) return null;
        return switch (m) {
            case PNG -> "image/png";
            case JPEG -> "image/jpeg";
            case GIF -> "image/gif";
            case WEBP -> "image/webp";
            case PDF -> "application/pdf";
            default -> null;
        };
    }

    private static boolean tooBig(String full, long limit) {
        try { return Files.size(Path.of(full)) > limit; } catch (IOException | RuntimeException e) { return false; }
    }

    private static String basename(String p) { int i = p.lastIndexOf('/'); return i < 0 ? p : p.substring(i + 1); }

    private static final Set<String> AV_EXT = Set.of("mp4", "mov", "avi", "mkv", "webm", "wmv", "flv", "m4v", "mpeg", "mpg",
            "mp3", "wav", "ogg", "flac", "aac", "m4a", "wma", "aiff", "opus");

    private static boolean audioVideo(String path) {
        int i = path.lastIndexOf('.');
        if (i < 0 || i == path.length() - 1) return false;
        return AV_EXT.contains(path.substring(i + 1).toLowerCase(Locale.ROOT));
    }

    private static Object readImage(ToolEnv env, String path, String full, String mime) throws IOException {
        if (tooBig(full, MEDIA_IMAGE_MAX_BYTES)) {
            return q(path) + " 超过了单张图直读的上限（" + MEDIA_IMAGE_MAX_BYTES + " 字节，约 3.6MB）。"
                    + "先用 run-command 缩：convert " + q(path) + " -resize 1562x1562 " + q(basename(path)) + ".small.png 再读它。";
        }
        byte[] bv = Files.readAllBytes(Path.of(full));
        String b64 = Base64.getEncoder().encodeToString(bv);
        Block.Image block = new Block.Image(mime, b64);
        // B 路：挂会话附件（随下一条消息带出）；挂件失败不拦工具结果
        ToolEnv.Attacher att = env.service(ToolEnv.Attacher.class);
        if (att != null) {
            try { att.attach(path, block); } catch (RuntimeException ignored) { }
        }
        // 图片也是文件，写它同样要走 seen 校验；base64 与字节一一对应
        Seen.of(env.workspace()).note(full, b64);
        return List.<Block>of(new Block.Text("[图片 " + path + "：" + mime + "，" + (bv.length / 1024) + "KB，已直读]"), block);
    }

    private static Object readPdf(ToolEnv env, String path, String full) throws IOException {
        DocumentSupport ds = env.service(DocumentSupport.class);
        if (ds != null && !ds.supported()) {
            return q(path) + " 是一份 PDF，但当前 provider 的工具结果不支持文档块。"
                    + "用 run-command 配 skill 自己抽文本（pdftotext " + q(path) + " - 就是入口），或让用户换到支持的 provider。";
        }
        if (tooBig(full, MEDIA_PDF_MAX_BYTES)) {
            return q(path) + " 超过了 PDF 直读的上限（" + MEDIA_PDF_MAX_BYTES + " 字节，约 20MB）。"
                    + "先用 run-command 拆：pdftk " + q(path) + " cat 1-20 输出前 20 页再读。";
        }
        byte[] bv = Files.readAllBytes(Path.of(full));
        String b64 = Base64.getEncoder().encodeToString(bv);
        Seen.of(env.workspace()).note(full, b64);
        return List.<Block>of(new Block.Text("[PDF " + path + "：application/pdf，" + (bv.length / 1024) + "KB，已直读]"),
                new Block.Document("application/pdf", b64));
    }

    private static String kindLabel(Mime m) {
        return switch (m) {
            case PNG -> "PNG";
            case JPEG -> "JPEG";
            case GIF -> "GIF";
            case WEBP -> "WebP";
            case BMP -> "BMP";
            default -> "";
        };
    }

    /** 不是 UTF-8 文本：图 / PDF 直读，其余拒绝并指路。这一支不登记 seen（什么正文都没交出去）。 */
    private static Object binaryRefusal(ToolEnv env, String path, String full) throws IOException {
        Mime kind = sniffFile(full);
        String media = mediaMime(kind);
        if (media != null && kind != Mime.PDF) return readImage(env, path, full, media);
        if (kind == Mime.PDF) return readPdf(env, path, full);
        if (audioVideo(path)) {
            return q(path) + " 是音视频文件，这一版不直读（两家参照实现也都不读）。用 run-command：ffprobe -hide_banner " + q(path)
                    + " 查信息，ffmpeg -i " + q(path) + " -ss 00:01 -frames:v 1 frame.png 抽一帧当图读。";
        }
        if (kind == Mime.BMP) {
            return q(path) + " 是一张 " + kindLabel(kind) + " 图片（按文件开头的魔数认的，不看扩展名），但读它的时候出了问题。"
                    + "用 run-command：ffprobe -hide_banner " + q(path) + " 查信息。";
        }
        return q(path) + " 看起来是二进制文件，不是 UTF-8 文本，没有读取。可以用 run-command 处理：ffprobe -hide_banner " + q(path)
                + " 查信息 / ffmpeg -i " + q(path) + " -ss 00:01 -frames:v 1 frame.png 抽帧。";
    }

    // ── read-file ─────────────────────────────────────────────────────

    public static AgentTool readFile() {
        return AgentTool.builder("read-file")
                .description("读取一个文本文件的内容。路径相对于工作根目录。第一行是行数摘要，第二行是 "
                        + "[路径#TAG]，TAG 是这份内容的标识；用 edit-file / write-file 改这个文件时"
                        + "必须原样带上它。正文每行前面的 \"行号:\" 是**显示用的**，"
                        + "抄原文给 edit-file 时不要带上它。"
                        + "文件太长或只想看一段时，用 offset / limit 分页：offset 是 1 起算的行号，"
                        + "limit 是行数上限；读的不是整篇时，末尾的尾注会告诉你下一页的 offset。"
                        + "图片（PNG/JPEG/GIF/WebP）与 PDF 按文件内容直读，直接返回给模型；音视频不直读但指路处理命令。")
                .required("path", "string", "文件路径，例如 src/main.c")
                // 哨兵 0 = 没传：分不清"没传"与"传了默认值"
                .optional("offset", "integer", "起始行号（1 起算），默认从头", 0L)
                .optional("limit", "integer", "读取的行数上限，默认整篇", 0L)
                .readOnly().concurrencySafe()
                .handler(FileTools::runReadFile)
                .build();
    }

    static Object runReadFile(ToolEnv env, io.ailink.agentforge.agent.tool.Args a) throws IOException {
        Workspace ws = env.workspace();
        String path = a.requireStr("path");
        long offset = a.integer("offset", 0), limit = a.integer("limit", 0);
        String full = Limit.mustExist(ws, path, Limit.resolveInRoot(ws, path), Limit.Want.FILE);
        FileText ft = Vfs.read(ws, full);
        if (ft.notText()) return binaryRefusal(env, path, full);
        String text = ft.text();
        int total = ToolText.lineCountNoTrailing(text);
        if (offset < 0 || limit < 0) throw new ToolException("offset / limit 不能是负数（offset 从 1 起算；0 表示用默认值）");
        // 只在 offset 显式给了时才拒：默认路径读空文件要照常回"0 行"；这一支也不登记 seen
        if (offset > 0 && offset > total) {
            throw new ToolException("offset=" + offset + " 越过了文件末尾（" + q(path) + " 共 " + total + " 行）");
        }
        // 登记的是整篇（分页只影响看得见多少，不影响"我读的时候它是这份"）
        Seen.of(ws).note(full, text);
        int off = offset == 0 ? 1 : (int) offset;
        Numbered nb = numbered(text, off, (int) Math.min(limit, Integer.MAX_VALUE), FILE_LIMIT);
        int last = nb.last();
        boolean more = last < total;
        boolean partial = off > 1 || more;
        String tail;
        if (nb.cut()) {
            tail = "\n…（第 " + off + "–" + last + " 行，共 " + total + " 行；输出到 " + FILE_LIMIT + " 字符封顶，用 offset=" + (last + 1) + " 继续）";
        } else if (!partial) {
            tail = "";
        } else if (more) {
            tail = "\n…（第 " + off + "–" + last + " 行，共 " + total + " 行；用 offset=" + (last + 1) + " 继续）";
        } else {
            tail = "\n…（第 " + off + "–" + last + " 行，共 " + total + " 行）";
        }
        // 第一行是摘要（给 UI），TAG 行在第二行
        String summary = partial ? "第 " + off + "–" + last + " 行（共 " + total + " 行）" : total + " 行";
        return summary + "\n[" + path + "#" + Tags.contentTag(text) + "]\n" + nb.body() + tail;
    }

    // ── list-dir ──────────────────────────────────────────────────────

    /** 目录列表（attach 的 @ 目录引用与 list-dir 逐字一致）。 */
    public static String dirListing(String full, String path) throws IOException {
        List<String> names = new ArrayList<>();
        List<String> sorted = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of(full))) {
            for (Path e : ds) names.add(e.getFileName().toString());
        }
        names.sort(null);
        if (names.isEmpty()) return "（空目录）";
        for (String nm : names) sorted.add(Files.isDirectory(Path.of(full, nm)) ? nm + "/" : nm);
        return names.size() + " 项\n" + Limit.truncateNote(String.join("\n", sorted), ENTRY_LIMIT, q(path) + " 的列表");
    }

    public static AgentTool listDir() {
        return AgentTool.builder("list-dir")
                .description("列出一个目录下的条目。目录名后面带 /。路径相对于工作根目录。")
                .required("path", "string", "目录路径，用 \".\" 表示工作根目录")
                .readOnly().concurrencySafe()
                .handler((env, a) -> {
                    String path = a.requireStr("path");
                    Workspace ws = env.workspace();
                    String full = Limit.mustExist(ws, path, Limit.resolveInRoot(ws, path), Limit.Want.DIR);
                    return dirListing(full, path);
                })
                .build();
    }

    // ── write-file ────────────────────────────────────────────────────

    public static AgentTool writeFile() {
        return AgentTool.builder("write-file")
                .description("把内容写进一个文件（整个覆盖）。路径相对于工作根目录，中间不存在的目录会自动创建。"
                        + "⚠️ 要覆盖一个已经存在的文件，先用 read-file 看一眼 —— 没读过的文件写不进去。"
                        + "新建文件不用先读。改一两处不要用这个，用 edit-file：这件工具只用来新建文件或整篇重写。"
                        + "⚠️ 新建之前先想清楚放在哪儿：path 要带上目录，新文件跟同一类的文件待在一起"
                        + "（先用 list-dir / find-files 看看这个项目把这类东西放在哪儿），"
                        + "别只给一个文件名把它丢在工作根目录 —— 根目录是项目自己的顶层文件待的地方。"
                        + "⚠️ 用户没要就别新建文件：说明、README、总结、报告这类 *.md，以及跑一趟用的"
                        + "临时脚本和中间产物，都不要写进项目里。要说的话直接说给用户听，"
                        + "不是写成一个文件让他自己去翻。能改已有文件就别新建。")
                .required("path", "string", "文件路径，相对于工作根目录，要带上目录，例如 src/parser/lexer.c")
                .required("content", "string", "完整的文件内容")
                .optional("tag", "string", "可省：这个文件最近一次读出来时的 TAG。传了会一起校验", "")
                // 不并发：这几件共享同一个 vfs / 暂存层，两件并排写的先后没有定义
                .sensitive().stageable()
                .handler(FileTools::runWriteFile)
                .build();
    }

    static Object runWriteFile(ToolEnv env, io.ailink.agentforge.agent.tool.Args a) {
        Workspace ws = env.workspace();
        String path = a.requireStr("path");
        String content = a.requireStr("content");
        String tag = a.str("tag", "");
        String full = Limit.resolveInRoot(ws, path);
        boolean exists = Vfs.exists(ws, full);
        // 是目录要在读之前判：只攒在端口里的路径一定是文件
        boolean dir = exists && !Vfs.isBufferAddress(full) && Files.isDirectory(Path.of(full));
        if (dir) throw new ToolException(q(path) + " 是一个目录，不能往目录上写。要建它下面的文件就写 " + path + "/<文件名>");
        FileText ft = exists ? Vfs.read(ws, full) : null;
        // 能读的才能写：read-file 不给看的二进制，write-file 也不许整篇换掉
        if (exists && ft.notText()) throw new ToolException(q(path) + " 看起来不是 UTF-8 文本（可能是二进制文件），没有覆盖它");
        if (exists) {
            String why = Seen.of(ws).check(path, full, ft.text(), tag);
            if (why != null) throw new ToolException(why);
        }
        // 新建时 before 是空串：新建一个语法就坏的文件照样拦
        String worse = Check.notWorse(ws, path, exists ? ft.text() : "", content);
        if (worse != null) throw new ToolException(worse);
        Vfs.write(ws, full, content,
                exists ? ft.eol() : FileText.platformEol(),
                exists ? ft.coding() : FileText.Coding.UTF_8,
                exists && ft.bom());
        // 写成功之后才登记，并把新 TAG 还回去：接着再改不用重读
        Seen.of(ws).note(full, content);
        return "已" + (exists ? "覆盖" : "新建") + " " + q(path) + "（" + content.length() + " 个字符）。新的 TAG 是 " + Tags.contentTag(content);
    }

}
