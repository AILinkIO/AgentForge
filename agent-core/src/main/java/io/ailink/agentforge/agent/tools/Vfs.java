package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.Workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 文件工具碰盘的唯一端口（对应 coxswain {@code agent-tools-vfs}）。
 *
 * <p>为什么要有这一层：宿主可以换实现 —— 编辑器在场时换成 buffer 支撑的（agent 与用户看同一份），
 * 批量审批时换成 {@link Staging}（改动先攒着，整轮结束再一起给人看）。工具只调这里的三个动作。
 *
 * <p>路径一律是 {@link Limit#resolveInRoot} 给出的完整路径字符串；{@code buffer:名字} 是编辑器缓冲区地址，
 * 不认识缓冲区的实现必须拒绝它（否则盘上会多出一个叫 {@code buffer:*buffer*} 的文件，而工具照样回"已写入"）。
 */
public interface Vfs {

    /** 读。不存在时抛（"在不在"是 {@link #exists} 的活）。 */
    FileText read(String path) throws IOException;

    /** 写：三样（eol / coding / bom）一起传。 */
    void write(String path, String text, FileText.Eol eol, FileText.Coding coding, boolean bom) throws IOException;

    boolean exists(String path);

    // ── 缓冲区地址 ─────────────────────────────────────────────────────

    String BUFFER_PREFIX = "buffer:";

    static boolean isBufferAddress(String path) { return path != null && path.startsWith(BUFFER_PREFIX); }

    static String bufferName(String path) { return isBufferAddress(path) ? path.substring(BUFFER_PREFIX.length()) : null; }

    // ── 默认实现：直接读写盘 ──────────────────────────────────────────

    Vfs DISK = new Vfs() {
        private void noBuffers(String path) {
            if (isBufferAddress(path)) {
                throw new ToolException("这个运行环境里没有编辑器缓冲区，" + ToolText.q(path) + " 打不开。缓冲区地址只在编辑器里有意义");
            }
        }

        @Override
        public FileText read(String path) throws IOException {
            noBuffers(path);
            return FileText.read(Path.of(path));
        }

        @Override
        public void write(String path, String text, FileText.Eol eol, FileText.Coding coding, boolean bom) throws IOException {
            noBuffers(path);
            // 父目录先建出来：写到不存在的子目录是模型自己修得了的错，不该漏出临时文件名
            FileText.write(Path.of(path), text, eol, coding, bom);
        }

        @Override
        public boolean exists(String path) { return !isBufferAddress(path) && Files.exists(Path.of(path)); }
    };

    /** 写完之后叫索引跟上的注入口子（codegraph 之类）。宿主 {@code ws.put(ReindexHook.class, …)}。 */
    @FunctionalInterface
    interface ReindexHook extends Consumer<String> { }

    /** 当前工作区用的实现（没装就是 {@link #DISK}）。 */
    static Vfs of(Workspace ws) {
        Vfs v = ws.get(Vfs.class);
        return v == null ? DISK : v;
    }

    /** 宿主换实现（编辑器 / staging）。传 null 退回盘。 */
    static void install(Workspace ws, Vfs v) { ws.put(Vfs.class, v == null ? DISK : v); }

    static FileText read(Workspace ws, String path) {
        try {
            return of(ws).read(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean exists(Workspace ws, String path) { return of(ws).exists(path); }

    static void write(Workspace ws, String path, String text, FileText.Eol eol, FileText.Coding coding, boolean bom) {
        writeOn(of(ws), ws, path, text, eol, coding, bom);
    }

    /**
     * 显式指定实现的写（staging 落盘提交走它）。写成功之后才叫重扫，重扫失败不许把成功的写变成报错。
     */
    static void writeOn(Vfs v, Workspace ws, String path, String text, FileText.Eol eol, FileText.Coding coding, boolean bom) {
        try {
            v.write(path, text, eol, coding, bom);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (ws != null) {
            ReindexHook h = ws.get(ReindexHook.class);
            if (h != null) {
                try { h.accept(path); } catch (RuntimeException ignored) { }
            }
        }
    }
}
