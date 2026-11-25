package io.ailink.agentforge.sandbox.execpolicy;

/** 规则文件写错了（语法、参数、match / not_match 自检没过）。消息给人看，带「文件:行:列」。 */
public class ExecPolicyException extends RuntimeException {
    public ExecPolicyException(String message) {
        super(message);
    }
}
