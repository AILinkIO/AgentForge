package io.ailink.agentforge.agent.models;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** state 目录里那几份小 JSON 的读写：读坏了当空、写失败吞掉（状态丢了只是"下次重选"，不该拦住启动）。 */
final class StateFile {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private StateFile() { }

    static JsonNode read(Path p) {
        if (p == null || !Files.isRegularFile(p)) return null;
        try {
            return JSON.readTree(Files.readAllBytes(p));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean write(Path p, JsonNode node) {
        if (p == null) return false;
        try {
            Path dir = p.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.write(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
