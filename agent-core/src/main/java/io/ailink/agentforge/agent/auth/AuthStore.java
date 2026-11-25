package io.ailink.agentforge.agent.auth;

import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.models.EnvVars;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API key 存哪儿（对应 coxswain {@code auth.ss}，形状抄 opencode 的 {@code auth.json}）。
 *
 * <p>为什么不只用环境变量：key 得写进 shell rc（会被 cat、被 dotfiles 仓库收走）；环境变量对**子进程全可见**，
 * 而 agent 会跑用户的构建脚本 —— 那些脚本因此也拿得到你的 key。所以另开一份**只有自己能读的**凭据文件：
 *
 * <pre>{@code { "<provider-id>": { "type": "api", "key": "…" }, … } }</pre>
 *
 * 用 AES-256-GCM 封成 {@code auth.json.enc}（密钥见 {@link SecretKeys}），0600。
 *
 * <ul>
 *   <li>按 provider 分条；{@code type} 字段今天只有 {@code "api"}，留着它是给将来的 OAuth 条目 ——
 *       现在留比将来给一份装着别人 key 的文件做迁移便宜。</li>
 *   <li>⚠️ 老的明文 {@code auth.json} 第一次读到就加密搬走并**删掉明文**；加密写不出来时不删（宁可留明文也不弄丢 key）。</li>
 *   <li>⚠️ 0600 仍然要：加密不是不设权限的理由，两道各挡一类事。</li>
 *   <li>⚠️ 任何地方都不打印 key 的内容，错误消息里也不带。</li>
 *   <li>取 key 的顺序（显式 → 环境变量 → 这份文件）在 {@code Models} 里，这一层只管存取。</li>
 * </ul>
 */
public final class AuthStore {

    /** 凭据文件现在是不是只有自己能读。⚠️ Windows 上问不出口（ACL 与三组位不同构）→ {@code NOT_APPLICABLE}。 */
    public enum FileMode { OK, LOOSE, MISSING, NOT_APPLICABLE }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path pathOverride;
    private final SecretKeys secrets;

    public AuthStore() { this(null, null); }

    /**
     * @param path    密文文件；null = {@code $AGENTFORGE_AUTH} 或 {@code <配置目录>/auth.json.enc}
     * @param secrets 密钥；null = 默认 {@link SecretKeys}
     */
    public AuthStore(Path path, SecretKeys secrets) {
        this.pathOverride = path;
        this.secrets = secrets != null ? secrets : new SecretKeys(path == null ? null : path.toAbsolutePath().resolveSibling(".auth-key"), null);
    }

    public SecretKeys secrets() { return secrets; }

    public Path path() {
        if (pathOverride != null) return pathOverride;
        String env = EnvVars.get("AGENTFORGE_AUTH");
        return env != null ? Path.of(env) : AgentPaths.configDir().resolve("auth.json.enc");
    }

    /** 搬家的来源：同目录下的明文 {@code auth.json}。只用来搬一次。 */
    public Path legacyPath() { return path().toAbsolutePath().resolveSibling("auth.json"); }

    /**
     * 读整份。读不到 / 打不开 / 不是 JSON 都当空 —— 一份坏掉的凭据文件不该拦住启动。
     * ⚠️ "打不开"包括换了一把密钥（密钥文件被删了、从别的机器拷来的密文）：那时 key 确实没了，这一层只能不假装成功。
     */
    private synchronized ObjectNode load() {
        Path p = path();
        if (Files.isRegularFile(p)) {
            try {
                byte[] plain = secrets.open(Files.readAllBytes(p));
                return plain == null ? JSON.createObjectNode() : parse(new String(plain, StandardCharsets.UTF_8));
            } catch (Exception e) {
                return JSON.createObjectNode();
            }
        }
        return migrate();
    }

    private ObjectNode migrate() {
        Path old = legacyPath();
        if (!Files.isRegularFile(old)) return JSON.createObjectNode();
        ObjectNode data;
        try {
            data = parse(Files.readString(old));
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
        if (!data.isEmpty() && save(data)) {
            try { Files.deleteIfExists(old); } catch (Exception ignored) { }
        }
        return data;
    }

    private static ObjectNode parse(String text) {
        try {
            JsonNode n = JSON.readTree(text);
            return n != null && n.isObject() ? (ObjectNode) n : JSON.createObjectNode();
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private boolean save(ObjectNode data) {
        byte[] sealed;
        try {
            sealed = secrets.seal(JSON.writeValueAsBytes(data));
        } catch (Exception e) {
            return false;
        }
        if (sealed == null) return false;
        try {
            SecretKeys.Files0600.write(path(), sealed);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 一家的 key，或 null。⚠️ 只认 {@code type} 是 {@code "api"}（或没写）的条目：将来的 OAuth 条目里那个字段
     * 不是能直接当 Bearer 用的东西，拿去发请求得到 401，排查方向会指向"key 不对"。
     */
    public String ref(String providerId) {
        if (providerId == null) return null;
        JsonNode e = load().get(providerId);
        if (e == null || !e.isObject()) return null;
        JsonNode type = e.get("type");
        JsonNode key = e.get("key");
        if (type != null && !(type.isString() && "api".equals(type.stringValue()))) return null;
        return key != null && key.isString() && !key.stringValue().isEmpty() ? key.stringValue() : null;
    }

    /** 存过哪几家。**只报名字，不报 key**。 */
    public List<String> kinds() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : load().properties()) out.add(e.getKey());
        return out;
    }

    /**
     * 存一条（封起来 + 0600），返回写完之后的权限状态。拿不到密钥时**抛** —— 不静静地写一份明文回去：
     * "存好了"与"存成了明文"在界面上长得一模一样。
     */
    public synchronized FileMode set(String providerId, String key) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("key 应当是一个非空字符串");
        if (providerId == null || providerId.isEmpty()) throw new IllegalArgumentException("provider id 应当是非空字符串");
        ObjectNode cur = load();
        ObjectNode entry = JSON.createObjectNode();
        entry.put("type", "api");
        entry.put("key", key);
        cur.set(providerId, entry);
        if (!save(cur)) throw new IllegalStateException("存不了凭据：拿不到加密密钥（HOME 没设、或者配置目录写不动）");
        return fileMode();
    }

    public synchronized void remove(String providerId) {
        ObjectNode cur = load();
        if (cur.remove(providerId) != null) save(cur);
    }

    /** 凭据现在是不是密文存着（搬家失败时是 false）。 */
    public boolean encrypted() { return Files.isRegularFile(path()) && !Files.exists(legacyPath()); }

    /**
     * 文件现在是不是只有自己能读。单独一个入口：别人也会动这个文件（vim 过、scp 过来、被 umask 027 的脚本重建过），
     * 真正要回答的是"它现在安不安全"。
     */
    public FileMode fileMode() {
        Path p = path();
        if (!Files.isRegularFile(p)) return FileMode.MISSING;
        if (!p.getFileSystem().supportedFileAttributeViews().contains("posix")) return FileMode.NOT_APPLICABLE;
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
            for (PosixFilePermission x : perms) {
                if (x.name().startsWith("GROUP") || x.name().startsWith("OTHERS")) return FileMode.LOOSE;
            }
            return FileMode.OK;
        } catch (Exception e) {
            return FileMode.LOOSE;
        }
    }
}
