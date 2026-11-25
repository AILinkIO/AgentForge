package io.ailink.agentforge.tui;

/** 给用户看的错误（Emacs 的 user-error）：命令抛它，命令循环只把消息放进 echo area，不记栈。消息过 i18n。 */
public class UserError extends RuntimeException {
    public UserError(String fmt, Object... args) { super(I18n.format(fmt, args)); }
}
