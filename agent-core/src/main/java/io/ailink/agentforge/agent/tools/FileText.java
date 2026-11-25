package io.ailink.agentforge.agent.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读出来的一份文本：内存里一律是 {@code \n} 行尾；落盘时按原样的 eol / 编码 / BOM 写回。
 *
 * <p>对应 keel 的 {@code filetext}。三样（eol / coding / bom）必须一起带着走：少带一样不会报错，
 * 只会静默改掉文件（UTF-8 with BOM 改一处 BOM 就没了）。
 *
 * @param why 解码依据：{@code null} = 正常 UTF-8；见 {@link Why}
 */
public record FileText(String path, String text, Eol eol, Coding coding, boolean bom, Why why, long stamp) {

    public enum Eol { LF, CRLF, CR }

    public enum Coding { UTF_8, LATIN_1 }

    /** 不是正常 UTF-8 时的依据。COOKIE = 文件自己声明了 latin-1，是真文本。 */
    public enum Why { BINARY, UTF8_RAW, GUESS, COOKIE }

    public FileText {
        text = text == null ? "" : text;
        eol = eol == null ? Eol.LF : eol;
        coding = coding == null ? Coding.UTF_8 : coding;
    }

    /** 新建文件用的平台默认行尾。 */
    public static Eol platformEol() { return Limit.WINDOWS ? Eol.CRLF : Eol.LF; }

    /** "读出来的不是 UTF-8 文本"的唯一判据（read / write / edit / form / attach 共用）。 */
    public boolean notText() {
        if (why != null) return why == Why.BINARY || why == Why.UTF8_RAW || why == Why.GUESS;
        return coding == Coding.LATIN_1;
    }

    public FileText withText(String t) { return new FileText(path, t, eol, coding, bom, why, stamp); }

    private static final Pattern COOKIE = Pattern.compile("-\\*-.*coding:\\s*(latin-1|iso-8859-1)", Pattern.CASE_INSENSITIVE);

    /** 解码一份字节。 */
    public static FileText decode(String path, byte[] bytes, long stamp) {
        int off = 0;
        boolean bom = false;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            bom = true;
            off = 3;
        }
        boolean nul = false;
        for (int i = off; i < bytes.length; i++) if (bytes[i] == 0) { nul = true; break; }
        String raw;
        Coding coding = Coding.UTF_8;
        Why why = null;
        if (nul) {
            raw = new String(bytes, off, bytes.length - off, StandardCharsets.ISO_8859_1);
            why = Why.BINARY;
        } else {
            try {
                raw = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
            } catch (CharacterCodingException e) {
                raw = new String(bytes, off, bytes.length - off, StandardCharsets.ISO_8859_1);
                if (hasLatin1Cookie(raw)) {
                    coding = Coding.LATIN_1;
                    why = Why.COOKIE;
                } else why = Why.UTF8_RAW;
            }
        }
        Eol eol = detectEol(raw);
        return new FileText(path, toLf(raw, eol), eol, coding, bom, why, stamp);
    }

    private static boolean hasLatin1Cookie(String s) {
        int nl = s.indexOf('\n');
        int nl2 = nl < 0 ? -1 : s.indexOf('\n', nl + 1);
        String head = nl2 < 0 ? s : s.substring(0, nl2);
        Matcher m = COOKIE.matcher(head);
        return m.find();
    }

    /** Emacs 的规矩：所有换行都是 CRLF 才算 CRLF；混合的按 LF，零星的 \r 留在文本里。 */
    static Eol detectEol(String s) {
        int lf = 0, crlf = 0, cr = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                lf++;
                if (i > 0 && s.charAt(i - 1) == '\r') crlf++;
            } else if (c == '\r') cr++;
        }
        if (lf > 0) return crlf == lf ? Eol.CRLF : Eol.LF;
        return cr > 0 ? Eol.CR : Eol.LF;
    }

    static String toLf(String s, Eol eol) {
        return switch (eol) {
            case LF -> s;
            case CRLF -> s.replace("\r\n", "\n");
            case CR -> s.replace('\r', '\n');
        };
    }

    /** 把 \n 文本按 eol 还原。 */
    public static String fromLf(String s, Eol eol) {
        return switch (eol) {
            case LF -> s;
            case CRLF -> s.replace("\n", "\r\n");
            case CR -> s.replace('\n', '\r');
        };
    }

    /** 文本里的 \r\n 归成 \n（edit-file 对 CRLF 文件用）。 */
    public static String normalizeCrlf(String s) { return s.replace("\r\n", "\n"); }

    // ── 盘 ──────────────────────────────────────────────────────────────

    public static FileText read(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        long stamp = Files.getLastModifiedTime(p).toMillis();
        return decode(p.toString(), bytes, stamp);
    }

    public static byte[] encode(String text, Eol eol, Coding coding, boolean bom) {
        String s = fromLf(text, eol);
        byte[] body = s.getBytes(coding == Coding.LATIN_1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        if (!bom) return body;
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    /** 原子替换写：同目录临时文件 + rename；保留原有权限。写到一半崩溃不会留下半个文件。 */
    public static void write(Path p, String text, Eol eol, Coding coding, boolean bom) throws IOException {
        Path dir = p.toAbsolutePath().getParent();
        if (dir != null) {
            try { Files.createDirectories(dir); } catch (IOException ignored) { }
        }
        byte[] bytes = encode(text, eol, coding, bom);
        Path tmp = Files.createTempFile(dir, "." + p.getFileName() + ".", ".agentforge-tmp");
        try {
            Files.write(tmp, bytes);
            try {
                // 临时文件生来是 0600；覆盖时沿用原权限，新建时给 0644（与普通 open 在常见 umask 下一致）
                Set<PosixFilePermission> perms = Files.exists(p)
                        ? Files.getPosixFilePermissions(p)
                        : java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");
                Files.setPosixFilePermissions(tmp, perms);
            } catch (IOException | UnsupportedOperationException ignored) {
                // 非 POSIX：用默认权限
            }
            try {
                Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static boolean isDir(Path p) { return Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(p); }
}
