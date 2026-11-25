package io.ailink.agentforge.agent.approval;

import java.util.ArrayList;
import java.util.List;

/**
 * 装密钥的文件（{@code .env} / {@code .env.*}，模板后缀除外）。Codex 没有对应物（它靠沙盒的 deny-read 条目，那会让命令永远不能出沙盒、
 * 也没有「批一次」的口子）；这里保留 coxswain 的做法：工具参数里任何一个词的 basename 是这种文件，就要先问 ——
 * 理由是<b>不可逆</b>：密钥进了上下文就收不回来。
 *
 * <p>⚠️ 启发式：看的是词，不是 shell 语义（{@code cat $F} 拦不住），不是安全保证。
 */
public final class SecretFiles {
    private SecretFiles() { }

    private static final List<String> EXEMPT = List.of(".example", ".sample", ".template", ".dist");

    /** 参数单行串（{@code path=.env limit=20}、{@code command=cat .env}）里有没有碰到密钥文件。 */
    public static boolean mentions(String oneLine) {
        if (oneLine == null) return false;
        for (String tok : tokens(oneLine)) if (secretName(basename(valueOf(tok)))) return true;
        return false;
    }

    static boolean secretName(String b) {
        if (!(b.equals(".env") || b.startsWith(".env."))) return false;
        for (String suf : EXEMPT) if (b.endsWith(suf)) return false;
        return true;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == ' ') {
                if (i > start) out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        return out;
    }

    private static String valueOf(String tok) {
        int i = tok.indexOf('=');
        String v = i < 0 ? tok : tok.substring(i + 1);
        // 引号、分号这类粘在词上的标点去掉（"cat '.env';"）
        int a = 0, b = v.length();
        while (a < b && "'\"`;(".indexOf(v.charAt(a)) >= 0) a++;
        while (b > a && "'\"`;)".indexOf(v.charAt(b - 1)) >= 0) b--;
        return v.substring(a, b);
    }

    private static String basename(String s) {
        int i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return i < 0 ? s : s.substring(i + 1);
    }
}
