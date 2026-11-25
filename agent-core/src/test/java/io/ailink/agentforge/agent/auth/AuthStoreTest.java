package io.ailink.agentforge.agent.auth;

import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.models.EnvVars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 移植自 coxswain tests/agent-auth-test.ss（凭据文件 + 密钥）。不碰真实密钥库。 */
class AuthStoreTest {

    @TempDir Path tmp;
    AuthStore store;

    @BeforeEach
    void setUp() {
        AgentPaths.override(tmp);
        EnvVars.override(new HashMap<>());
        store = new AuthStore(tmp.resolve("auth.json.enc"), new SecretKeys(tmp.resolve(".auth-key"), Keystore.NONE));
    }

    @AfterEach
    void tearDown() {
        EnvVars.reset();
        AgentPaths.override(null);
    }

    @Test
    void 存取删() throws Exception {
        assertNull(store.ref("deepseek"));
        assertEquals(AuthStore.FileMode.MISSING, store.fileMode());
        AuthStore.FileMode m = store.set("deepseek", "sk-secret-123");
        assertNotEquals(AuthStore.FileMode.LOOSE, m);
        assertEquals("sk-secret-123", store.ref("deepseek"));
        store.set("minimax", "sk-2");
        assertEquals(List.of("deepseek", "minimax"), store.kinds().stream().sorted().toList());
        byte[] raw = Files.readAllBytes(store.path());
        assertFalse(new String(raw, StandardCharsets.ISO_8859_1).contains("sk-secret"), "盘上是密文");
        assertTrue(store.encrypted());
        store.remove("deepseek");
        assertNull(store.ref("deepseek"));
        assertEquals("sk-2", store.ref("minimax"));
        assertThrows(IllegalArgumentException.class, () -> store.set("x", ""));
        if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(AuthStore.FileMode.OK, store.fileMode());
            assertEquals(32, Files.size(tmp.resolve(".auth-key")));
        }
    }

    @Test
    void 只认api类型的条目() throws Exception {
        Files.writeString(store.legacyPath(), "{\"a\":{\"type\":\"oauth\",\"key\":\"tok\"},\"b\":{\"key\":\"kb\"}}");
        assertNull(store.ref("a"), "OAuth 条目不能直接当 key 用");
        assertEquals("kb", store.ref("b"), "没写 type 当 api");
    }

    @Test
    void 明文搬家后删掉() throws Exception {
        Files.writeString(store.legacyPath(), "{\"deepseek\":{\"type\":\"api\",\"key\":\"old\"}}");
        assertFalse(store.encrypted());
        assertEquals("old", store.ref("deepseek"));
        assertFalse(Files.exists(store.legacyPath()), "搬完删掉明文");
        assertTrue(store.encrypted());
        assertEquals("old", store.ref("deepseek"));
    }

    @Test
    void 换了密钥就打不开而且不炸() throws Exception {
        store.set("deepseek", "k");
        Files.delete(tmp.resolve(".auth-key"));
        AuthStore other = new AuthStore(tmp.resolve("auth.json.enc"), new SecretKeys(tmp.resolve(".auth-key"), Keystore.NONE));
        assertNull(other.ref("deepseek"));
        assertEquals(List.of(), other.kinds());
    }

    @Test
    void 密钥来路() {
        SecretKeys s = new SecretKeys(tmp.resolve("k1"), Keystore.NONE);
        assertEquals(SecretKeys.Source.NEW_FILE, s.source());
        byte[] sealed = s.seal("你好".getBytes(StandardCharsets.UTF_8));
        assertEquals("你好", new String(s.open(sealed), StandardCharsets.UTF_8));
        sealed[sealed.length - 1] ^= 1;
        assertNull(s.open(sealed), "被改过 → 打不开");
        assertNull(s.open(new byte[3]));
        SecretKeys again = new SecretKeys(tmp.resolve("k1"), Keystore.NONE);
        assertEquals(SecretKeys.Source.FILE, again.source());

        // 密钥库赢；头一回会试着存进去
        AtomicReference<String> stored = new AtomicReference<>();
        Keystore fake = new Keystore() {
            @Override public String lookup() { return stored.get() == null ? null : stored.get() + "\n"; }
            @Override public boolean store(String hex) { stored.set(hex); return true; }
        };
        SecretKeys k2 = new SecretKeys(tmp.resolve("k2"), fake);
        assertEquals(SecretKeys.Source.NEW_FILE, k2.source());
        assertNotNull(stored.get());
        assertEquals(64, stored.get().length());
        SecretKeys k3 = new SecretKeys(tmp.resolve("k2"), fake);
        assertEquals(SecretKeys.Source.KEYSTORE, k3.source());
        assertArrayEquals(k2.key(), k3.key());
        assertNull(SecretKeys.fromHex("abc"));
        assertNull(SecretKeys.fromHex(" " + "0".repeat(64)), "Unicode 空白是值的一部分");
    }
}
