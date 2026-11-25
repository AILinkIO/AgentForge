package io.ailink.agentforge.agent.tools.web;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Args;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tools.Limit;
import io.ailink.agentforge.agent.util.Fmt;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * web-fetch：抓一个 URL 给模型读（coxswain {@code tools/fetch.ss}，对齐 opencode webfetch）。
 *
 * <ul>
 *   <li>HTML 默认转 markdown；{@code format=text} 只留正文；{@code html} 原样。非 HTML 一律原样。</li>
 *   <li>响应体 5MB 封顶（超了不读、指路 curl -r）；正文 40000 字符截断，完整那份落盘（再出一次网要付代价）。</li>
 *   <li>图片（png/jpeg/gif/webp，≤ 3.75MB）直接作为图片块交回，并经 {@link ToolEnv.Attacher} 附进对话。</li>
 *   <li>sensitive（出网，URL 本身会送出去）；并发安全。</li>
 * </ul>
 */
public final class WebFetch {
    private WebFetch() { }

    public static final int FETCH_OUTPUT_LIMIT = 40000;
    static final int MAX_BODY = 5 * 1024 * 1024;
    static final int DEFAULT_TIMEOUT_S = 30;
    static final int MAX_TIMEOUT_S = 120;
    static final String USER_AGENT = "agentforge-agent/1.0";
    static final int IMAGE_MAX_BYTES = 3_750_000;
    static final List<String> IMAGE_MIMES = List.of("image/png", "image/jpeg", "image/gif", "image/webp");

    public enum Format { MARKDOWN, TEXT, HTML }

    public static AgentTool tool() {
        return AgentTool.builder("web-fetch")
                .description("抓一个 URL 的内容给你读。HTML 默认转成 markdown（format=markdown）；"
                        + "format=text 只留正文文字，format=html 给原样 HTML。"
                        + "不是 HTML 的响应（markdown、JSON、纯文本）无论要什么格式都原样给。"
                        + "URL 必须是完整的 http:// 或 https://。响应体 5 MB 封顶；"
                        + "正文太长会截断并把完整内容落到一个文件里，注记里有路径，"
                        + "用 run-command 的 grep / sed -n 看需要的那段，别再抓一遍。"
                        + "图片（png / jpeg / gif / webp）直接给你看；别的图或太大的会指路。"
                        + "⚠️ 有更合适的专门工具（MCP 给的浏览器 / 文档工具）时优先用那个。")
                .required("url", "string", "完整的 URL，以 http:// 或 https:// 开头")
                .optional("format", "string", "markdown（默认）/ text / html", "markdown")
                .optional("timeout", "integer", "超时秒数，默认 30，上限 120；0 表示用默认", 0L)
                .sensitive().concurrencySafe()
                .handler(WebFetch::run)
                .build();
    }

    // ── 纯函数 ─────────────────────────────────────────────────────────

    public static boolean httpUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("https://")) return url.length() > 8;
        if (url.startsWith("http://")) return url.length() > 7;
        return false;
    }

    /** 空串 / markdown → MARKDOWN；认不出 → null。 */
    public static Format format(String s) {
        if (s == null || s.isEmpty() || s.equals("markdown")) return Format.MARKDOWN;
        if (s.equals("text")) return Format.TEXT;
        if (s.equals("html")) return Format.HTML;
        return null;
    }

    /** Content-Type → 小写的 mime（切掉 {@code ;} 之后）；没有 → ""。 */
    public static String mime(String contentType) {
        if (contentType == null) return "";
        int i = contentType.indexOf(';');
        return (i < 0 ? contentType : contentType.substring(0, i)).strip().toLowerCase(Locale.ROOT);
    }

    static boolean htmlMime(String mime) { return mime.equals("text/html") || mime.equals("application/xhtml+xml"); }

    public record Rendered(String text, String how) { }

    /** 字节 + mime + 要的格式 → (正文, 转法说明)。 */
    public static Rendered render(byte[] bytes, String contentType, Format fmt) {
        String mime = mime(contentType);
        if (htmlMime(mime)) {
            String html = decode(bytes, contentType, true);
            return switch (fmt) {
                case MARKDOWN -> new Rendered(HtmlRender.markdown(Html.parse(html)), "HTML → markdown");
                case TEXT -> new Rendered(HtmlRender.text(Html.parse(html)), "HTML → 纯文本");
                case HTML -> new Rendered(html, "原样 HTML");
            };
        }
        return new Rendered(decode(bytes, contentType, false), "原样");
    }

    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)");

    /** 编码：Content-Type 的 charset → HTML 前 4KB 里的 meta charset → UTF-8。BOM 去掉。 */
    static String decode(byte[] bytes, String contentType, boolean sniffMeta) {
        Charset cs = null;
        if (contentType != null) cs = charset(contentType);
        if (cs == null && sniffMeta) {
            String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher m = Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)").matcher(head);
            if (m.find()) cs = forName(m.group(1));
        }
        if (cs == null) cs = StandardCharsets.UTF_8;
        int off = 0;
        if (cs == StandardCharsets.UTF_8 && bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) off = 3;
        return new String(bytes, off, bytes.length - off, cs);
    }

    private static Charset charset(String ct) {
        Matcher m = CHARSET.matcher(ct);
        return m.find() ? forName(m.group(1)) : null;
    }

    private static Charset forName(String n) {
        try { return Charset.forName(n); } catch (RuntimeException e) { return null; }
    }

    static String accept(Format f) {
        return switch (f) {
            case MARKDOWN -> "text/markdown;q=1.0, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1";
            case TEXT -> "text/plain;q=1.0, text/markdown;q=0.9, text/html;q=0.8, */*;q=0.1";
            case HTML -> "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, */*;q=0.1";
        };
    }

    // ── 出网 ───────────────────────────────────────────────────────────

    static final class TooLarge extends IOException {
        TooLarge() { super("max-body-size"); }
    }

    static Object run(ToolEnv env, Args a) throws Exception {
        String url = a.requireStr("url");
        String fmtName = a.str("format", "markdown");
        long timeout = a.integer("timeout", 0);
        Format fmt = format(fmtName);
        if (!httpUrl(url)) throw new ToolException("URL 必须以 http:// 或 https:// 开头，而且要带主机名：" + Fmt.q(url));
        if (fmt == null) throw new ToolException("format 只能是 markdown / text / html，收到 " + Fmt.q(fmtName));
        if (timeout < 0) throw new ToolException("timeout 不能是负数（秒；0 表示用默认 30）");
        long secs = timeout == 0 ? DEFAULT_TIMEOUT_S : Math.min(timeout, MAX_TIMEOUT_S);

        HttpResponse<InputStream> resp;
        byte[] body;
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(secs))
                .build();
        try (client) {
            HttpRequest req;
            try {
                req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(secs))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", accept(fmt))
                        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
                        .GET().build();
            } catch (IllegalArgumentException e) {
                throw new ToolException("抓 " + Fmt.q(url) + " 失败：" + e.getMessage());
            }
            CompletableFuture<HttpResponse<InputStream>> f = client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream());
            Runnable unhook = env.cancel().onCancel(() -> f.cancel(true));
            try {
                resp = f.get(secs + 5, java.util.concurrent.TimeUnit.SECONDS);
                body = readCapped(resp.body());
            } catch (java.util.concurrent.CancellationException e) {
                env.cancel().throwIfCancelled();
                throw new ToolException("抓 " + Fmt.q(url) + " 失败：被取消");
            } catch (TooLarge e) {
                throw new ToolException(Fmt.q(url) + " 的响应超过 " + (MAX_BODY / (1024 * 1024))
                        + " MB，没有读。要它的一部分就用 run-command：curl -r 0-1000000 " + Fmt.q(url));
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | IOException e) {
                Throwable c = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
                throw new ToolException("抓 " + Fmt.q(url) + " 失败：" + describe(c));
            } finally {
                unhook.run();
            }
        }
        int status = resp.statusCode();
        String ct = resp.headers().firstValue("content-type").orElse("");
        String mime = mime(ct);
        if (status < 200 || status >= 300) {
            String t = new String(body, StandardCharsets.UTF_8);
            String snippet = t.length() > 500 ? t.substring(0, 500) : t;
            throw new ToolException(Fmt.q(url) + " 回了 HTTP " + status + (snippet.isEmpty() ? "" : "。响应开头：" + snippet));
        }
        if (mime.startsWith("image/")) return image(env, url, mime, body);
        Rendered r = render(body, ct, fmt);
        return (mime.isEmpty() ? "抓到了" : "抓到 " + mime) + "，" + r.text().length() + " 字符（" + r.how() + "）\n"
                + Limit.truncateNoteSpill(r.text(), FETCH_OUTPUT_LIMIT, Fmt.q(url) + " 的内容");
    }

    static String describe(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : " " + m);
    }

    static byte[] readCapped(InputStream in) throws IOException {
        try (in) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) throw new TooLarge();
            return b;
        }
    }

    static Object image(ToolEnv env, String url, String mime, byte[] bv) {
        int n = bv.length;
        if (!IMAGE_MIMES.contains(mime)) {
            return Fmt.q(url) + " 是一张 " + mime + "（" + n + " 字节），模型只吃 png / jpeg / gif / webp。要看它就 run-command：curl -sL -o pic "
                    + Fmt.q(url) + " && convert pic pic.png，然后 read-file pic.png";
        }
        if (n > IMAGE_MAX_BYTES) {
            return Fmt.q(url) + " 是一张 " + mime + "，" + n + " 字节，超过了单张图直读的上限（" + IMAGE_MAX_BYTES
                    + " 字节，约 3.6MB）。先 run-command：curl -sL -o pic " + Fmt.q(url)
                    + " && convert pic -resize 1562x1562 pic.small.png 再 read-file 它";
        }
        Block.Image img = new Block.Image(mime, Base64.getEncoder().encodeToString(bv));
        ToolEnv.Attacher att = env.service(ToolEnv.Attacher.class);
        if (att != null) att.attach(url, img);
        return List.<Block>of(new Block.Text("抓到图片 " + url + "（" + mime + "，" + (n / 1024) + "KB，已直读）"), img);
    }
}
