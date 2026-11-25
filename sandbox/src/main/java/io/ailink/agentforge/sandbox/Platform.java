package io.ailink.agentforge.sandbox;

import java.util.Locale;

/** 当前操作系统（只看 {@code os.name}）。 */
public final class Platform {
    private Platform() {
    }

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    public static boolean isWindows() {
        return OS.startsWith("windows");
    }

    public static boolean isMacOS() {
        return OS.startsWith("mac") || OS.startsWith("darwin");
    }

    public static boolean isLinux() {
        return OS.startsWith("linux");
    }
}
