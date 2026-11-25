package io.ailink.agentforge.sandbox.exec;

import java.time.Duration;

/**
 * 起好的进程。{@link #close()}：还没退出 ⇒ 连同子进程树一起杀掉，再释放资源。
 */
public interface ExecProcess extends AutoCloseable {
    long pid();

    /** 退出码；还在跑 ⇒ {@code null}。被信号杀掉的（Unix）⇒ 128 + 信号号。 */
    Integer exitCode();

    /** 下一段输出（stdout 与 stderr 按到达次序交错）；两条流都读到头 ⇒ {@code null}。阻塞；只能有一个读者。 */
    OutputChunk nextOutput() throws InterruptedException;

    /** 往 stdin 写（进程已关 stdin ⇒ 静默丢弃）。 */
    void writeInput(byte[] data);

    /** 关 stdin（EOF）。 */
    void closeInput();

    /** 等退出，回退出码。 */
    int waitForExit() throws InterruptedException;

    /** 至多等 {@code timeout}；到点还没退出 ⇒ {@code null}。 */
    Integer waitForExit(Duration timeout) throws InterruptedException;

    /** 发中断（Unix SIGINT ≈ Ctrl-C）；平台做不到 ⇒ false。 */
    boolean tryInterrupt();

    /** 杀掉（连同子进程树）；已退出不算错。 */
    void kill();

    @Override
    void close();
}
