package io.ailink.agentforge.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端是单页应用：前端路由认的那几条路径都交给 index.html（刷新 /chat/xxx 不 404）。
 *
 * <p>⚠️ 只列前端真有的路由，不要写 {@code /**} 兜底：控制器映射优先于静态资源，兜底会把 /assets/*.js 也吞成 index.html。
 */
@Controller
public class SpaController {
    @GetMapping({"/", "/new", "/chat/{id}", "/agents", "/history", "/models", "/credentials", "/permissions", "/tools"})
    public String index() {
        return "forward:/index.html";
    }
}
