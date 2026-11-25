package io.ailink.agentforge.agent.auth;

import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.models.EnvVars;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 凭据用哪把密钥封（对应 coxswain {@code secret.ss}；形状抄自 jinshu 的 {@code IConfigProtector}）：
 * **平台密钥库存一把 32 字节密钥，载荷自己用 AES-256-GCM 封** —— 而不是把整份凭据塞进密钥库
 * （密钥库存得下的是小串，每次存取还可能弹窗 / 走 D-Bus）。
 *
 * <p>密钥来路，第一个拿得到的赢：①平台密钥库（{@link Keystore}）②{@code <配置目录>/.auth-key}（每次安装
 * 随机生成的 32 字节，0600）。⚠️ 第②条不是"没做完"：它防磁盘扫描、备份、误提交；它**不防**能读你 home
 * 的进程（密钥和密文一起拿走就完了）。如实说清楚比含糊地叫"已加密"重要。
 *
 * <p>⚠️ 头一回：先写文件（一定成的那条路），再**试着**存进密钥库。文件里已经有一把时**不再往密钥库里塞**：
 * 那会造出第二个真相源，下次密钥库赢了的话用文件密钥封的凭据就打不开了。
 * ⚠️ 任何地方都不打印密钥；{@link #source()} 只报来路。
 *
 * <p>密文格式：{@code 12 字节 nonce ‖ 密文 ‖ 16 字节 tag}。
 */
public final class SecretKeys {

    public enum Source { KEYSTORE, FILE, NEW_FILE }

    private static final String SERVICE = "agentforge";
    private static final int NONCE = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path keyPathOverride;
    private final Keystore keystore;
    private byte[] key;
    private Source source;

    /** 默认：密钥文件跟着凭据文件走，密钥库按平台（{@code $AGENTFORGE_NO_KEYRING} 关掉）。 */
    public SecretKeys() { this(null, null); }

    /**
     * @param keyPath  密钥文件；null = 默认位置
     * @param keystore 密钥库；null = 按平台（测试传 {@link Keystore#NONE}，免得碰开发者真实的那把）
     */
    public SecretKeys(Path keyPath, Keystore keystore) {
        this.keyPathOverride = keyPath;
        this.keystore = keystore != null ? keystore
                : EnvVars.get("AGENTFORGE_NO_KEYRING") != null ? Keystore.NONE
                : Keystore.platform(SERVICE, keyPathFor(keyPath).resolveSibling(".auth-key.dpapi"));
    }

    /**
     * 密钥文件位置：{@code $AGENTFORGE_SECRET_KEY} → 跟着 {@code $AGENTFORGE_AUTH} 那个目录 → 配置目录。
     * ⚠️ 跟着凭据文件走：测试把 AUTH 指到别处时密钥也去那儿，否则会用上开发者真实的那把。
     */
    public Path keyPath() { return keyPathFor(keyPathOverride); }

    private static Path keyPathFor(Path override) {
        if (override != null) return override;
        String env = EnvVars.get("AGENTFORGE_SECRET_KEY");
        if (env != null) return Path.of(env);
        String auth = EnvVars.get("AGENTFORGE_AUTH");
        if (auth != null) {
            Path dir = Path.of(auth).toAbsolutePath().getParent();
            if (dir != null) return dir.resolve(".auth-key");
        }
        return AgentPaths.configDir().resolve(".auth-key");
    }

    /** 进程内缓存（不缓存的话每次取 key 都要 spawn 一次 secret-tool）。 */
    public synchronized void forget() {
        key = null;
        source = null;
    }

    /** 32 字节密钥；拿不到（目录不可写）时 null，由调用方决定怎么说。 */
    public synchronized byte[] key() {
        if (key != null) return key;
        byte[] fromStore = fromHex(keystore.lookup());
        if (fromStore != null) {
            key = fromStore;
            source = Source.KEYSTORE;
            return key;
        }
        byte[] fromFile = readKeyFile();
        if (fromFile != null) {
            key = fromFile;
            source = Source.FILE;
            return key;
        }
        byte[] fresh = new byte[32];
        RANDOM.nextBytes(fresh);
        if (!writeKeyFile(fresh)) return null;
        keystore.store(HexFormat.of().formatHex(fresh));
        key = fresh;
        source = Source.NEW_FILE;
        return key;
    }

    /** 密钥从哪儿来；拿不到时 null。只报来路，不报内容。 */
    public synchronized Source source() {
        key();
        return source;
    }

    /** 明文 → 密文；没有密钥时 null。 */
    public byte[] seal(byte[] plain) {
        byte[] k = key();
        if (k == null) return null;
        try {
            byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[NONCE + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE);
            System.arraycopy(ct, 0, out, NONCE, ct.length);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 密文 → 明文；打不开（密钥换了、文件被改过、不是我们的格式）时 null。 */
    public byte[] open(byte[] sealed) {
        byte[] k = key();
        if (k == null || sealed == null || sealed.length < NONCE + 16) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, sealed, 0, NONCE));
            return c.doFinal(sealed, NONCE, sealed.length - NONCE);
        } catch (Exception e) {
            return null;
        }
    }

    // ⚠️ 只认 64 位 hex，两端只剥 ASCII 空白（命令行输出带的换行）：Unicode 空白是值的一部分
    static byte[] fromHex(String s) {
        if (s == null) return null;
        int b = 0, e = s.length();
        while (b < e && " \n\r\t".indexOf(s.charAt(b)) >= 0) b++;
        while (e > b && " \n\r\t".indexOf(s.charAt(e - 1)) >= 0) e--;
        String t = s.substring(b, e);
        if (t.length() != 64) return null;
        try {
            return HexFormat.of().parseHex(t);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private byte[] readKeyFile() {
        Path p = keyPath();
        try {
            if (!Files.isRegularFile(p)) return null;
            byte[] bv = Files.readAllBytes(p);
            return bv.length == 32 ? bv : null;
        } catch (IOException e) {
            return null;
        }
    }

    // 目录 0700、文件 0600：父目录松的话，文件权限挡不住"能进目录就能替换"
    private boolean writeKeyFile(byte[] k) {
        Path p = keyPath();
        try {
            Files0600.write(p, k);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 0700 目录 + 0600 文件的写法（POSIX 上；Windows 没有这组位，照常写）。 */
    static final class Files0600 {
        private Files0600() { }

        static void write(Path p, byte[] data) throws IOException {
            Path abs = p.toAbsolutePath();
            Path dir = abs.getParent();
            boolean posix = abs.getFileSystem().supportedFileAttributeViews().contains("posix");
            if (dir != null && !Files.isDirectory(dir)) {
                if (posix) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                else Files.createDirectories(dir);
            }
            Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            if (posix) Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(tmp, data);
            Files.move(tmp, abs, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
