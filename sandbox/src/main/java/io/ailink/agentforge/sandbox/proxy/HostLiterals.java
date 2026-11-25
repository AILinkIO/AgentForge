package io.ailink.agentforge.sandbox.proxy;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 字面 IP 的解析与规范文本（⛔ DNS：域名策略要在解析前判）。IPv4 是点分十进制四段；IPv6 按 RFC 5952 压缩、小写（≙ .NET 的文本形式）。
 */
public final class HostLiterals {
    private HostLiterals() {
    }

    /** 是字面 IP ⇒ 地址；否则 {@code null}（不查 DNS）。 */
    public static InetAddress parseLiteral(String s) {
        if (s == null || s.isEmpty()) return null;
        if (s.indexOf(':') >= 0) {
            String body = s;
            int pct = body.indexOf('%');
            if (pct >= 0) body = body.substring(0, pct);
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (!(Character.digit(c, 16) >= 0 || c == ':' || c == '.')) return null;
            }
            try {
                // 含冒号的串 InetAddress 只当字面解析，不会查 DNS
                return InetAddress.getByName(s.contains("[") ? s : "[" + s + "]");
            } catch (UnknownHostException | SecurityException e) {
                return null;
            }
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            for (int k = 0; k < p.length(); k++) if (p.charAt(k) < '0' || p.charAt(k) > '9') return null;
            int v = Integer.parseInt(p);
            if (v > 255) return null;
            b[i] = (byte) v;
        }
        try {
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** 规范文本：IPv4 点分；IPv6 压缩最长的零段（长度 ≥ 2）、小写。 */
    public static String format(InetAddress address) {
        if (address instanceof Inet4Address) return address.getHostAddress();
        if (!(address instanceof Inet6Address)) return address.getHostAddress();
        byte[] b = address.getAddress();
        int[] w = new int[8];
        for (int i = 0; i < 8; i++) w[i] = ((b[2 * i] & 0xFF) << 8) | (b[2 * i + 1] & 0xFF);
        int bestStart = -1, bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (w[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && w[j] == 0) j++;
            if (j - i > bestLen) {
                bestStart = i;
                bestLen = j - i;
            }
            i = j;
        }
        if (bestLen < 2) bestStart = -1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                sb.append("::");
                i += bestLen - 1;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') sb.append(':');
            sb.append(Integer.toHexString(w[i]));
        }
        return sb.toString();
    }
}
