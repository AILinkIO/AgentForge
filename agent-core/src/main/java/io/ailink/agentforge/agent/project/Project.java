package io.ailink.agentforge.agent.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一个项目（照 Codex 桌面端 / ChatGPT 的 Projects）：一个名字 + 一个或几个源文件夹（工作区）+ 这个项目自己的设置。
 * 会话开在某个项目的某个源文件夹里（那个文件夹就是会话的工作根），对话记着它属于哪个项目。
 *
 * @param id           稳定 id（建时生成，改名不变）
 * @param name         显示名
 * @param folders      源文件夹（绝对路径，至少一个；第一个是缺省工作根）
 * @param instructions 项目指令：拼进这个项目所有会话的系统提示（可空）
 * @param model        这个项目新会话的缺省模型 ref（可空 = 跟全局）
 * @param preset       审批 × 沙盒预设 id：read-only / auto / full-access（可空 = 跟全局）
 * @param created      建立时刻（epoch 毫秒）
 * @param updated      最后修改时刻
 */
public record Project(String id, String name, List<String> folders, String instructions, String model, String preset,
                      long created, long updated) {

    public Project {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("项目要有名字");
        name = name.strip();
        if (folders == null || folders.isEmpty()) throw new IllegalArgumentException("项目至少要有一个源文件夹");
        folders = folders.stream().map(f -> Path.of(f).toAbsolutePath().normalize().toString()).distinct().toList();
        instructions = blankToNull(instructions);
        model = blankToNull(model);
        preset = blankToNull(preset);
    }

    /** 缺省工作根（第一个源文件夹）。 */
    public Path root() { return Path.of(folders.getFirst()); }

    /** 会话要开在哪个文件夹：给的是这个项目的源文件夹之一就用它，否则用缺省的那个。 */
    public Path folderOrDefault(String folder) {
        if (folder != null) {
            String f = Path.of(folder).toAbsolutePath().normalize().toString();
            if (folders.contains(f)) return Path.of(f);
        }
        return root();
    }

    /** 某个路径是不是在这个项目的某个源文件夹里（含文件夹本身）。 */
    public boolean contains(Path p) {
        Path n = p.toAbsolutePath().normalize();
        for (String f : folders) if (n.startsWith(Path.of(f))) return true;
        return false;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
