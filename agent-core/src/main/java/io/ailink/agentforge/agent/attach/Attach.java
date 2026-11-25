package io.ailink.agentforge.agent.attach;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.FileText;
import io.ailink.agentforge.agent.tools.Limit;
import io.ailink.agentforge.agent.tools.Tags;
import io.ailink.agentforge.agent.tools.ToolText;
import io.ailink.agentforge.agent.tools.Vfs;
import io.ailink.agentforge.agent.tools.file.FileTools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static io.ailink.agentforge.agent.tools.ToolText.q;

/**
 * 用户侧附件：把盘上的图片附进对话、或者处理输入框里的 {@code @路径} 提及（对应 coxswain {@code agent/attach.ss}）。
 *
 * <p>与 read-file 走同一道沙箱与同一个 vfs 端口，文本那一支输出与 read-file 不带分页时逐字同形。
 * ⚠️ @ 提及**不登记 seen**：那是用户贴给模型看的，不是模型自己读的 —— 登记了，模型就能不读就覆盖它。
 */
public final class Attach {
    private Attach() { }

    /** 单张图上限（原始字节）：3.75MB 编出来约 5MB，正是 Anthropic 单图上限。 */
    public static final long IMAGE_MAX_BYTES = 3_750_000L;
    /** 嗅探 / NUL 闸只读前这么多字节。 */
    static final int SNIFF_BYTES = 8000;

    public enum Kind { TEXT, IMAGE, DIRECTORY, FAILED }

    /** attach-file 的结果：kind + 块（FAILED 时为 null）+ 失败原因（成功时为 null）。 */
    public record Result(Kind kind, Block block, String reason) {
        static Result failed(String why) { return new Result(Kind.FAILED, null, why); }
    }

    /** 只认字节的媒体类型（PNG / JPEG / GIF / WebP / PDF），认不出回 null。 */
    public static String imageMediaType(byte[] bv) {
        FileTools.Mime m = FileTools.sniffMime(bv);
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

    /** 路径（相对工作根，限定在根下）→ 图片块；认不出 / 太大 / 是 PDF 抛 {@link ToolException}（文字给人看）。 */
    public static Block.Image attachImage(Workspace ws, String path) {
        String full = Limit.mustExist(ws, path, Limit.resolveInRoot(ws, path), Limit.Want.FILE);
        return attachImageAt(full, path);
    }

    static Block.Image attachImageAt(String full, String path) {
        Path p = Path.of(full);
        // 先看大小再读：200MB 的文件读进来只为发现它太大，是白占内存
        Long size = null;
        try { size = Files.size(p); } catch (IOException ignored) { }
        if (size != null && size > IMAGE_MAX_BYTES) throw tooBig(path, size);
        byte[] bv;
        try { bv = Files.readAllBytes(p); } catch (IOException e) { throw new ToolException(q(path) + " 打不开，没有读成"); }
        String mime = imageMediaType(bv);
        if ("application/pdf".equals(mime)) {
            throw new ToolException(q(path) + " 是一份 PDF，这一版还交不了 PDF（两家的 PDF 走的是另一种块，不是图）。先导出成 PNG / JPEG。");
        }
        if (mime == null) {
            throw new ToolException(q(path) + " 看起来不是 PNG / JPEG / GIF / WebP（按文件开头的字节认的，不看扩展名），没有附上。");
        }
        // 再守一遍：上面那次 stat 拿不到时整个跳过了；保不变量的这一处不许"拿不到就算了"
        if (bv.length > IMAGE_MAX_BYTES) throw tooBig(path, bv.length);
        return new Block.Image(mime, Base64.getEncoder().encodeToString(bv));
    }

    private static ToolException tooBig(String path, long size) {
        return new ToolException(q(path) + " 有 " + size + " 字节，超过了单张图的上限 " + IMAGE_MAX_BYTES + " 字节（约 3.6 MB）。先缩一下再附。");
    }

    /** {@code ~} / 绝对 / 相对 的展开（@ 提及不限定在根下：用户自己点的路径）。 */
    static String expand(Workspace ws, String path) {
        if (Vfs.isBufferAddress(path)) return path;
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/")) return Path.of(System.getProperty("user.home"), path.substring(2)).toString();
        Path p = Path.of(path);
        return (p.isAbsolute() ? p : ws.root().resolve(p)).normalize().toString();
    }

    /** @ 提及：路径 → 文本 / 图片 / 目录列表块，或失败原因。永不抛。 */
    public static Result attachFile(Workspace ws, String path) {
        try {
            String full = expand(ws, path);
            if (!Vfs.exists(ws, full)) return Result.failed("文件不存在：" + q(path));
            if (!Vfs.isBufferAddress(full) && Files.isDirectory(Path.of(full))) {
                return new Result(Kind.DIRECTORY, new Block.Text(FileTools.dirListing(full, path)), null);
            }
            return attachFileFile(ws, path, full);
        } catch (ToolException e) {
            return Result.failed(e.getMessage());
        } catch (Exception e) {
            return Result.failed(q(path) + " 没有读成");
        }
    }

    private static Result attachFileFile(Workspace ws, String path, String full) {
        Path p = Path.of(full);
        if (Vfs.isBufferAddress(full) || !Files.exists(p)) return attachFileText(ws, path, full);   // 只在端口里：一定是文本
        long size;
        byte[] prefix;
        try {
            size = Files.size(p);
            try (InputStream in = Files.newInputStream(p)) { prefix = in.readNBytes(SNIFF_BYTES); }
        } catch (IOException e) {
            return Result.failed(q(path) + " 打不开，没有读成");
        }
        String mime = imageMediaType(prefix);
        if ("application/pdf".equals(mime)) {
            return Result.failed(q(path) + " 是一份 PDF，@ 引用这一版交不了（两家的 PDF 走的是 document 块，不是图）。read-file 能直读它 —— 让模型用 read-file 读。");
        }
        if (mime != null) {
            if (size > IMAGE_MAX_BYTES) {
                return Result.failed(q(path) + " 有 " + size + " 字节，超过了单张图的上限 " + IMAGE_MAX_BYTES + " 字节（约 3.6 MB）。先缩一下再附。");
            }
            return new Result(Kind.IMAGE, attachImageAt(full, path), null);
        }
        for (byte b : prefix) {
            if (b == 0) return Result.failed(q(path) + " 是二进制文件（前 " + SNIFF_BYTES + " 字节里有 NUL），附上了模型也读不了，没有附。");
        }
        if (size > FileTools.FILE_LIMIT) {
            return Result.failed(q(path) + " 有 " + size + " 字节，超过一次能附的上限 " + FileTools.FILE_LIMIT + " 字节。太大的文件让模型用 read-file 读它需要的部分。");
        }
        return attachFileText(ws, path, full);
    }

    private static Result attachFileText(Workspace ws, String path, String full) {
        FileText ft = Vfs.read(ws, full);
        if (ft.notText()) {
            return Result.failed(q(path) + " 看起来不是 UTF-8 文本（可能是二进制文件），没有附。要是图或 PDF，read-file 认得它们。");
        }
        String text = ft.text();
        return new Result(Kind.TEXT, new Block.Text(ToolText.lineCountNoTrailing(text) + " 行\n[" + path + "#" + Tags.contentTag(text) + "]\n"
                + Limit.truncateNote(numbered(text), FileTools.FILE_LIMIT, q(path))), null);
    }

    /** 整篇编号（不分页）。 */
    private static String numbered(String text) {
        StringBuilder out = new StringBuilder();
        int start = 0, ln = 1;
        for (int i = 0; i <= text.length(); i++) {
            boolean end = i == text.length();
            if (!end && text.charAt(i) != '\n') continue;
            if (end && i <= start) break;
            out.append(ln).append(':').append(FileTools.clipLine(text.substring(start, i)));
            if (!end) out.append('\n');
            ln++;
            start = i + 1;
        }
        return out.toString();
    }
}
