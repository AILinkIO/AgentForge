package io.ailink.agentforge.sandbox.linux;

import io.ailink.agentforge.sandbox.ffm.Libc;

/**
 * libc 文件调用的 Java 面（statx / realpath / faccessat / geteuid）。JDK 的 {@code Files} 拿不到「按有效身份判可写（含 ACL）」
 * 与不跟链接的 uid，bwrap 的信任判定要这些。
 */
public final class LinuxFs {
    private LinuxFs() {
    }

    public static final int W_OK = Libc.W_OK;
    public static final int X_OK = Libc.X_OK;
    public static final int EACCES = Libc.EACCES;
    public static final int EROFS = Libc.EROFS;

    /** 文件类型。 */
    public enum Type { SYMLINK, DIRECTORY, FILE, OTHER }

    /** statx 的结果。 */
    public record Stat(int mode, int uid, Type type) {
        public boolean isSymlink() {
            return type == Type.SYMLINK;
        }

        public boolean isDirectory() {
            return type == Type.DIRECTORY;
        }

        public boolean isRegularFile() {
            return type == Type.FILE;
        }
    }

    /** statx；{@code follow} false ⇒ lstat 语义；不存在 ⇒ {@code null}。 */
    public static Stat stat(String path, boolean follow) {
        int[] r = Libc.stat(path, follow);
        if (r == null) return null;
        int mode = r[0];
        Type t = switch (mode & 0xF000) {
            case 0xA000 -> Type.SYMLINK;
            case 0x4000 -> Type.DIRECTORY;
            case 0x8000 -> Type.FILE;
            default -> Type.OTHER;
        };
        return new Stat(mode, r[1], t);
    }

    /** 路径（不跟链接）存在。 */
    public static boolean lexists(String path) {
        return stat(path, false) != null;
    }

    /** realpath：全解符号链接；不存在 ⇒ {@code null}。 */
    public static String realPath(String path) {
        return Libc.realPath(path);
    }

    public static int effectiveUid() {
        return Libc.effectiveUid();
    }

    /** faccessat(…, AT_EACCESS)：0 ⇒ 可；否则 errno。 */
    public static int accessEffective(String path, int mode) {
        return Libc.accessEffective(path, mode);
    }
}
