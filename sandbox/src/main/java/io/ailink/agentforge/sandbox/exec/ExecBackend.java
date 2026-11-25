package io.ailink.agentforge.sandbox.exec;

import java.io.IOException;

/**
 * 执行后端：进程类工具只经它起进程 ⇒ 本机、沙盒、远端都能接。沙盒的做法是包装一个内层后端（改写请求再交给它）。
 */
public interface ExecBackend {
    ExecCapabilities capabilities();

    /** 起进程。程序不存在等启动失败照常抛 {@link IOException}。 */
    ExecProcess start(ExecRequest request) throws IOException;
}
