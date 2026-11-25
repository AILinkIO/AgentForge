package io.ailink.agentforge.sandbox;

/**
 * 错误的类别（≙ C# 版的异常类，keelson 里是 {@code :keelson/kind}）。调用方按 {@link SandboxException#code()} 判别具体错误，
 * 类别只用来粗分「参数错 / 状态不对 / IO / 平台不支持」。
 */
public enum ErrorKind {
    /** 参数不合法（如相对路径、空 argv）。 */
    ARGUMENT,
    /** 参数越界（如非正的超时）。 */
    ARGUMENT_OUT_OF_RANGE,
    /** 当前状态下不能做（后端不可用、策略做不到、重复启动）。 */
    INVALID_OPERATION,
    /** IO / 系统调用失败。 */
    IO,
    /** 策略 JSON 格式错。 */
    JSON,
    /** 后端不支持该请求（如伪终端）。 */
    NOT_SUPPORTED,
    /** 本平台不支持。 */
    PLATFORM_NOT_SUPPORTED
}
