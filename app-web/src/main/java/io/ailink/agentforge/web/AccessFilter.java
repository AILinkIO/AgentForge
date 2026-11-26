package io.ailink.agentforge.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 门禁。agent 能跑命令、改文件，所以：
 * <ul>
 *   <li>只听回环地址（缺省）时：Host 头必须是 localhost / 127.0.0.1 / [::1]（挡 DNS rebinding）；令牌可选。</li>
 *   <li>听别的地址时：必须带令牌（没配就随机生成一个，打在日志里）。</li>
 *   <li>令牌的递法：{@code Authorization: Bearer …}，或首次访问 {@code /?token=…} 换成 HttpOnly cookie。</li>
 *   <li>改东西的请求（非 GET）必须带 {@code X-AgentForge: 1}：别的网站的表单 / 简单请求带不了自定义头（挡 CSRF）。</li>
 * </ul>
 */
@Component
public class AccessFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AccessFilter.class);
    static final String COOKIE = "agentforge_token";
    static final String HEADER = "X-AgentForge";

    private final boolean loopback;
    private final String token;

    public AccessFilter(WebProperties props, @Value("${server.address:127.0.0.1}") String address,
                        @Value("${server.port:8787}") int port) {
        this.loopback = isLoopback(address);
        String t = props.token() == null || props.token().isBlank() ? null : props.token();
        if (t == null && !loopback) {
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            t = HexFormat.of().formatHex(b);
            log.warn("听的是 {}（不只是本机），已生成访问令牌：http://<host>:{}/?token={}", address, port, t);
        }
        this.token = t;
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) return false;          // 不给 = 所有网卡
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (loopback && !localHost(req.getHeader("Host"))) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN, "Host 不对");
            return;
        }
        String path = req.getRequestURI();
        boolean api = path.startsWith("/api/");
        if (token != null) {
            String q = req.getParameter("token");
            if (q != null && equal(q, token) && !api) {
                Cookie c = new Cookie(COOKIE, token);
                c.setHttpOnly(true);
                c.setPath("/");
                c.setAttribute("SameSite", "Strict");
                res.addCookie(c);
                res.sendRedirect("/");
                return;
            }
            if (api && !authorized(req)) {
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "要访问令牌");
                return;
            }
        }
        if (api && !"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod()) && !"1".equals(req.getHeader(HEADER))) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN, "缺 " + HEADER + " 头");
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean authorized(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ") && equal(h.substring(7).strip(), token)) return true;
        if (req.getCookies() != null)
            for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName()) && equal(c.getValue(), token)) return true;
        return false;
    }

    static boolean localHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 && h.substring(1, end).equals("::1");
        }
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.endsWith(".localhost");
    }

    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
