package io.ailink.agentforge.web;

import io.ailink.agentforge.agent.project.Project;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.store.SessionStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目（照 Codex 桌面端：名字 + 源文件夹 + 项目指令 / 默认模型 / 权限预设）与选文件夹用的服务器目录浏览。
 */
@RestController
@RequestMapping("/api")
public class ProjectController {
    static final int MAX_DIR_ENTRIES = 1000;

    private final SessionHub hub;
    private final AgentRuntime rt;

    public ProjectController(SessionHub hub) {
        this.hub = hub;
        this.rt = hub.runtime();
    }

    public record ProjectBody(String name, List<String> folders, String instructions, String model, String preset) { }

    public record MkdirBody(String parent, String name) { }

    // ── 项目 ───────────────────────────────────────────────────────────

    @GetMapping("/projects")
    public List<Map<String, Object>> list() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SessionStore.Conversation c : rt.conversations(0)) if (c.project() != null) counts.merge(c.project(), 1, Integer::sum);
        return rt.projects().list().stream().map(p -> Views.project(p, counts.getOrDefault(p.id(), 0))).toList();
    }

    @PostMapping("/projects")
    public Map<String, Object> create(@RequestBody ProjectBody b) {
        checkModel(b.model());
        Project p = rt.projects().create(b.name(), b.folders(), b.instructions(), b.model(), b.preset());
        hub.publish(Views.m().put("type", "projects").done());
        return Views.project(p, 0);
    }

    @PutMapping("/projects/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody ProjectBody b) {
        checkModel(b.model());
        Project p = rt.projects().update(id, b.name(), b.folders(), b.instructions(), b.model(), b.preset());
        hub.publish(Views.m().put("type", "projects").done());
        return Views.project(p, 0);
    }

    /** 移除项目：只从项目表里拿掉；文件夹里的文件、存着的对话都不动（对话归到「未归类」）。开着的会话不能有。 */
    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        for (AgentSession s : hub.sessions())
            if (id.equals(rt.projectOf(s))) throw new ResponseStatusException(HttpStatus.CONFLICT, "这个项目还有开着的会话，先关掉");
        if (!rt.projects().delete(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个项目");
        hub.publish(Views.m().put("type", "projects").done());
        return ResponseEntity.noContent().build();
    }

    private void checkModel(String ref) {
        if (ref != null && !ref.isBlank() && !rt.modelRefs().contains(ref)) throw new IllegalArgumentException("没有这个模型：" + ref);
    }

    // ── 服务器上的目录（选源文件夹用）──────────────────────────────────

    /** 列一个目录下的子目录（只列目录；隐藏目录要 {@code hidden=true}）。不给路径 = 家目录。 */
    @GetMapping("/fs")
    public Map<String, Object> browse(@RequestParam(required = false) String path, @RequestParam(defaultValue = "false") boolean hidden) {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path dir;
        try {
            dir = path == null || path.isBlank() ? home : Path.of(expand(path, home)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("路径不对：" + path);
        }
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("不是目录：" + dir);
        List<Map<String, Object>> dirs = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            List<Path> all = new ArrayList<>();
            for (Path c : ds) {
                if (!Files.isDirectory(c)) continue;
                String name = c.getFileName().toString();
                if (!hidden && name.startsWith(".")) continue;
                all.add(c);
            }
            all.sort(Comparator.comparing(c -> c.getFileName().toString().toLowerCase()));
            if (all.size() > MAX_DIR_ENTRIES) { truncated = true; all = all.subList(0, MAX_DIR_ENTRIES); }
            for (Path c : all)
                dirs.add(Views.m().put("name", c.getFileName().toString()).put("path", c.toString())
                        .put("git", Files.exists(c.resolve(".git"))).done());
        } catch (IOException e) {
            throw new IllegalArgumentException("读不了目录：" + dir + "（" + e.getMessage() + "）");
        }
        Path parent = dir.getParent();
        return Views.m().put("path", dir.toString()).put("parent", parent == null ? null : parent.toString())
                .put("home", home.toString()).put("git", Files.exists(dir.resolve(".git")))
                .put("dirs", dirs).put("truncated", truncated ? true : null).done();
    }

    /**
     * 选文件夹时的建议：家目录下两层以内的 git 仓库（最近改过的在前，至多 40 个），已经是某个项目源文件夹的标出来。
     */
    @GetMapping("/fs/suggest")
    public Map<String, Object> suggest() {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Project p : rt.projects().list()) used.addAll(p.folders());
        List<Path> repos = new ArrayList<>();
        scan(home, 0, repos);
        repos.sort(Comparator.comparingLong((Path p) -> mtime(p.resolve(".git"))).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path r : repos.subList(0, Math.min(40, repos.size())))
            out.add(Views.m().put("name", r.getFileName().toString()).put("path", r.toString())
                    .put("parent", home.relativize(r.getParent()).toString()).put("used", used.contains(r.toString())).done());
        return Views.m().put("home", home.toString()).put("repos", out).done();
    }

    private static void scan(Path dir, int depth, List<Path> out) {
        if (depth > 2 || out.size() > 400) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path c : ds) {
                String n = c.getFileName().toString();
                if (n.startsWith(".") || n.equals("node_modules") || n.equals("build") || n.equals("target")) continue;
                if (Files.isDirectory(c.resolve(".git")) || Files.isRegularFile(c.resolve(".git"))) out.add(c);
                else scan(c, depth + 1, out);
            }
        } catch (IOException | RuntimeException ignored) { }
    }

    private static long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }

    /** 在某个目录下新建一个子目录（名字不许带路径分隔符）。 */
    @PostMapping("/fs/mkdir")
    public Map<String, Object> mkdir(@RequestBody MkdirBody b) {
        if (b.name() == null || b.name().isBlank() || b.name().contains("/") || b.name().contains("\\") || b.name().equals("..") || b.name().equals("."))
            throw new IllegalArgumentException("文件夹名不对：" + b.name());
        Path parent = Path.of(b.parent()).toAbsolutePath().normalize();
        if (!Files.isDirectory(parent)) throw new IllegalArgumentException("不是目录：" + parent);
        Path dir = parent.resolve(b.name().strip());
        try {
            Files.createDirectory(dir);
        } catch (IOException e) {
            throw new IllegalArgumentException("建不了 " + dir + "：" + e.getMessage());
        }
        return browse(dir.toString(), false);
    }

    static String expand(String p, Path home) {
        if (p.equals("~")) return home.toString();
        if (p.startsWith("~/")) return home.resolve(p.substring(2)).toString();
        return p;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> onBad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
