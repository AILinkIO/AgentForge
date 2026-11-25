package io.ailink.agentforge.sandbox;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 沙盒抛的错误：带稳定错误码（{@link #code()}）、类别（{@link #kind()}）与文案（{@link #reason()}）。
 * 调用方按码判别，⛔ 比对消息（它随界面语言变）。原生层（FFM）用 {@link #ofNative} 只给码与参数，文案在这里按当前语言渲染。
 */
public class SandboxException extends RuntimeException {
    private final ErrorKind kind;
    private final transient Message reason;
    private final transient Object[] args;

    public SandboxException(ErrorKind kind, Message reason) {
        this(kind, reason, null);
    }

    public SandboxException(ErrorKind kind, Message reason, Throwable cause, Object... args) {
        super(reason.text(), cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.reason = reason;
        this.args = args == null ? new Object[0] : args.clone();
    }

    /** 按码造：{@code SandboxException.of(ErrorKind.ARGUMENT, "sandbox/path-not-absolute", path)}。 */
    public static SandboxException of(ErrorKind kind, String code, Object... args) {
        return new SandboxException(kind, Messages.message(code, args), null, args);
    }

    /** 原生层的错误（缺省类别 {@link ErrorKind#INVALID_OPERATION}）。 */
    public static SandboxException ofNative(String code, Object... args) {
        return of(ErrorKind.INVALID_OPERATION, code, args);
    }

    /** 换个类别重抛（原生层的码不变）。 */
    public SandboxException withKind(ErrorKind newKind) {
        if (newKind == kind) return this;
        SandboxException e = new SandboxException(newKind, reason, getCause(), args);
        e.setStackTrace(getStackTrace());
        return e;
    }

    public String code() {
        return reason.code();
    }

    public ErrorKind kind() {
        return kind;
    }

    public Message reason() {
        return reason;
    }

    public List<Object> args() {
        return Arrays.asList(args.clone());
    }
}
