package io.ailink.agentforge.agent.mcp;

/** MCP 客户端这一层的失败。子类按"用户下一步该干什么"分。 */
public class McpException extends RuntimeException {
    public McpException(String message) { super(message); }

    public McpException(String message, Throwable cause) { super(message, cause); }

    /** 对面回了 JSON-RPC error（服务器拒了 initialize、工具报错…）—— 原样念它的话。 */
    public static final class RpcError extends McpException {
        private final int code;

        public RpcError(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() { return code; }
    }

    /** 等超了。 */
    public static final class Timeout extends McpException {
        public Timeout(String message) { super(message); }
    }

    /** 连接没了（子进程退出 / 流断了 / 主动关了）。 */
    public static final class Closed extends McpException {
        public Closed(String message) { super(message); }
    }

    /** HTTP 层非 2xx。401/403 ⇒ {@link #isAuth()}，状态归 NEEDS_AUTH。 */
    public static final class HttpStatus extends McpException {
        private final int status;

        public HttpStatus(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }

        public boolean isAuth() { return status == 401 || status == 403; }
    }
}
