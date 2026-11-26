package io.ailink.agentforge.agent.project;

import io.ailink.agentforge.agent.approval.ApprovalPreset;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 项目表：存在配置目录的 {@code projects.json}（人能看懂、能手改；写入走临时文件 + 原子改名）。线程安全。
 *
 * <pre>{"projects": [{"id": "…", "name": "novels", "folders": ["/home/me/novels"], "instructions": "…",
 *                    "model": "minimax/MiniMax-M3.1-Flash", "preset": "auto", "created": 0, "updated": 0}]}</pre>
 *
 * 校验：名字非空、至少一个源文件夹、每个文件夹都是存在的目录、预设认得出。删项目不碰文件夹里的文件，也不删对话。
 */
public final class ProjectStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;
    private final Map<String, Project> projects = new LinkedHashMap<>();
    private final List<Consumer<Project>> listeners = new ArrayList<>();
    private String broken;

    public ProjectStore(Path file) {
        this.file = file;
        load();
    }

    public static Path file(Path configDir) { return configDir.resolve("projects.json"); }

    public Path path() { return file; }

    /** 文件读不了 / 不是合法 JSON 时的原因（这时只读、拒绝写，免得盖掉手改坏的文件）；正常是 null。 */
    public synchronized String broken() { return broken; }

    /** 全部项目，最近改过的在前。 */
    public synchronized List<Project> list() {
        return projects.values().stream().sorted(Comparator.comparingLong(Project::updated).reversed()).toList();
    }

    public synchronized Optional<Project> get(String id) {
        return Optional.ofNullable(id == null ? null : projects.get(id));
    }

    /** 第一个把这个文件夹列为源文件夹的项目。 */
    public synchronized Optional<Project> byFolder(Path folder) {
        String f = folder.toAbsolutePath().normalize().toString();
        return projects.values().stream().filter(p -> p.folders().contains(f)).findFirst();
    }

    public synchronized Project create(String name, List<String> folders, String instructions, String model, String preset) {
        long now = System.currentTimeMillis();
        Project p = validate(new Project(UUID.randomUUID().toString().substring(0, 8), name, folders, instructions, model, preset, now, now));
        projects.put(p.id(), p);
        save();
        return p;
    }

    /** 整份替换（id、created 沿用原来的）。改了之后通知监听者（开着的会话据此刷新系统提示）。 */
    public Project update(String id, String name, List<String> folders, String instructions, String model, String preset) {
        Project p;
        synchronized (this) {
            Project old = projects.get(id);
            if (old == null) throw new IllegalArgumentException("没有这个项目：" + id);
            p = validate(new Project(id, name, folders, instructions, model, preset, old.created(), System.currentTimeMillis()));
            projects.put(id, p);
            save();
        }
        for (Consumer<Project> l : List.copyOf(listeners)) {
            try { l.accept(p); } catch (RuntimeException ignored) { }
        }
        return p;
    }

    public synchronized boolean delete(String id) {
        boolean had = projects.remove(id) != null;
        if (had) save();
        return had;
    }

    /** 项目改了（update）时回调。 */
    public synchronized void onChange(Consumer<Project> l) { listeners.add(l); }

    private static Project validate(Project p) {
        for (String f : p.folders()) {
            if (!Files.isDirectory(Path.of(f))) throw new IllegalArgumentException("不是目录：" + f);
        }
        if (p.preset() != null) ApprovalPreset.parse(p.preset());
        return p;
    }

    // ── 读写文件 ──────────────────────────────────────────────────────────

    private synchronized void load() {
        projects.clear();
        if (!Files.isRegularFile(file)) return;
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            for (JsonNode n : root.path("projects")) {
                List<String> folders = new ArrayList<>();
                for (JsonNode f : n.path("folders")) folders.add(f.asString());
                try {
                    Project p = new Project(n.path("id").asString(), n.path("name").asString(), folders, text(n, "instructions"),
                            text(n, "model"), text(n, "preset"), n.path("created").asLong(), n.path("updated").asLong());
                    projects.put(p.id(), p);
                } catch (RuntimeException ignored) {
                    // 手改坏了的那一条跳过（文件夹不在了的照样留着：可能只是暂时没挂上）
                }
            }
        } catch (IOException e) {
            broken = "读不了项目表 " + file + "：" + e.getMessage();
        } catch (RuntimeException e) {
            broken = "项目表不是合法 JSON（修好之前不会改写它）：" + file + "：" + e.getMessage();
        }
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null || v.isNull() ? null : v.asString();
    }

    private void save() {
        if (broken != null) throw new IllegalStateException(broken);
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("projects");
        for (Project p : projects.values()) {
            ObjectNode n = arr.addObject();
            n.put("id", p.id()).put("name", p.name());
            ArrayNode fs = n.putArray("folders");
            p.folders().forEach(fs::add);
            if (p.instructions() != null) n.put("instructions", p.instructions());
            if (p.model() != null) n.put("model", p.model());
            if (p.preset() != null) n.put("preset", p.preset());
            n.put("created", p.created()).put("updated", p.updated());
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写不了项目表 " + file, e);
        }
    }
}
