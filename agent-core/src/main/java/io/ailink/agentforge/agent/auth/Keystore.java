package io.ailink.agentforge.agent.auth;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 平台密钥库：存 / 取一把 32 字节密钥的 hex 文本（对应 coxswain {@code secret.ss} 那张"命令 + 参数"表）。
 *
 * <p>存进去的是 base16 而不是 base64：解码只有一种写法，跨命令行工具时 base64 的换行 / 补 {@code =}
 * 正是最容易错的地方。
 *
 * <ul>
 *   <li>Linux / BSD：{@code secret-tool}（libsecret 的命令行面）</li>
 *   <li>macOS：{@code security}（钥匙串）</li>
 *   <li>Windows：PowerShell 调 DPAPI（{@code ProtectedData}，CurrentUser 作用域），密文放在密钥文件旁边的
 *       {@code .auth-key.dpapi} —— DPAPI 没有"按名字存"的库，它只会封与解。</li>
 * </ul>
 *
 * <p>⚠️ **值一律从 stdin 喂**，绝不放进命令行参数：参数在 {@code ps} 里对同机所有用户可见。
 * ⚠️ 任何失败（没装、没有 D-Bus 会话 —— ssh 进来的常态、用户取消）都只是"这条来路用不了"，返回 null / false。
 * ⚠️ 这几条在开发机上验不全（Linux 这台没有 secret-tool；macOS / Windows 只按文档写），错只会错在命令表里。
 */
public interface Keystore {

    /** hex 文本，或 null。 */
    String lookup();

    boolean store(String hex);

    /** 不用密钥库（测试，或 {@code $AGENTFORGE_NO_KEYRING}）。 */
    Keystore NONE = new Keystore() {
        @Override public String lookup() { return null; }
        @Override public boolean store(String hex) { return false; }
    };

    /** 按平台挑一张命令表。{@code dpapiFile} 只有 Windows 用。 */
    static Keystore platform(String service, Path dpapiFile) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return new Commands(
                List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "Add-Type -AssemblyName System.Security; $p='" + dpapiFile.toString().replace("'", "''") + "';"
                                + " if (Test-Path $p) { $b=[Convert]::FromBase64String((Get-Content -Raw $p).Trim());"
                                + " $k=[Security.Cryptography.ProtectedData]::Unprotect($b,$null,'CurrentUser');"
                                + " [BitConverter]::ToString($k).Replace('-','') } else { exit 1 }"),
                List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "Add-Type -AssemblyName System.Security; $h=[Console]::In.ReadToEnd().Trim();"
                                + " $k=[byte[]]::new($h.Length/2); for($i=0;$i -lt $k.Length;$i++){$k[$i]=[Convert]::ToByte($h.Substring(2*$i,2),16)};"
                                + " $b=[Security.Cryptography.ProtectedData]::Protect($k,$null,'CurrentUser');"
                                + " Set-Content -NoNewline -Path '" + dpapiFile.toString().replace("'", "''") + "' -Value ([Convert]::ToBase64String($b))"));
        if (os.contains("mac")) return new Commands(
                List.of("security", "find-generic-password", "-s", service, "-a", "auth-key", "-w"),
                List.of("security", "add-generic-password", "-s", service, "-a", "auth-key", "-U", "-w"));
        return new Commands(
                List.of("secret-tool", "lookup", service, "auth-key"),
                List.of("secret-tool", "store", "--label=" + service + " credential key", service, "auth-key"));
    }

    /** 一对命令：查的那条把值打到 stdout，存的那条从 stdin 读值。 */
    record Commands(List<String> lookupArgv, List<String> storeArgv) implements Keystore {
        @Override
        public String lookup() {
            try {
                Process p = new ProcessBuilder(lookupArgv).redirectErrorStream(false)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                p.getOutputStream().close();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = p.getInputStream()) { in.transferTo(buf); }
                if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
                return p.exitValue() == 0 ? buf.toString(StandardCharsets.UTF_8) : null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public boolean store(String hex) {
            try {
                Process p = new ProcessBuilder(storeArgv).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try (OutputStream out = p.getOutputStream()) { out.write(hex.getBytes(StandardCharsets.US_ASCII)); }
                if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
                return p.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
