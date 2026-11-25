package io.ailink.agentforge.agent.prompt;

/**
 * 提示词与内置 agent 表里提到的工具名，**只有这一处**。
 *
 * <p>⚠️ 提示词里写的名字必须与真正注册的工具同名 —— 对不上的话模型会去调一件不存在的工具。名字照 coxswain 原样
 * （连字符）；工具层如果改了命名，改这里一处，提示词与 {@code registry} 的内置 agent 一起跟上。
 */
public final class ToolNames {
    private ToolNames() { }

    public static final String READ_FILE = "read-file";
    public static final String LIST_DIR = "list-dir";
    public static final String WRITE_FILE = "write-file";
    public static final String EDIT_FILE = "edit-file";
    public static final String REPLACE_FORM = "replace-form";
    public static final String RUN_COMMAND = "run-command";
    public static final String SEARCH_TEXT = "search-text";
    public static final String FIND_FILES = "find-files";
    public static final String WEB_FETCH = "web-fetch";
    public static final String DELETE_PATH = "delete-path";
    public static final String MEMORY_LIST = "memory-list";
    public static final String MEMORY_WRITE = "memory-write";
    public static final String TODO_WRITE = "todo-write";
    public static final String TASK = "task";
    public static final String CLARIFY = "clarify";
    public static final String CURRENT_BUFFER = "current-buffer";
}
