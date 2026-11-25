package io.ailink.agentforge.agent.tools.file;

import io.ailink.agentforge.agent.tool.AgentTool;

import java.util.List;

/**
 * 文件工具一族，按 coxswain {@code %builtin} 里的相对顺序（模型按顺序读这张表）。
 *
 * <p>全局顺序（含别的包）：read-file list-dir search-text find-files find-symbol symbol-refs impact worktree-list
 * write-file edit-file delete-path list-forms read-form replace-form run-command apply-patch web-fetch web-search
 * memory-list memory-read memory-write skill todo-write worktree-create worktree-remove worktree-reset worktree-enter。
 */
public final class FileToolset {
    private FileToolset() { }

    public static AgentTool readFile() { return FileTools.readFile(); }

    public static AgentTool listDir() { return FileTools.listDir(); }

    public static AgentTool writeFile() { return FileTools.writeFile(); }

    public static AgentTool editFile() { return EditTool.editFile(); }

    public static AgentTool deletePath() { return DeleteTool.deletePath(); }

    public static AgentTool listForms() { return FormTools.listForms(); }

    public static AgentTool readForm() { return FormTools.readForm(); }

    public static AgentTool replaceForm() { return FormTools.replaceForm(); }

    public static AgentTool applyPatch() { return PatchTool.applyPatch(); }

    /** 九件，按上面那张全局表里的相对顺序。 */
    public static List<AgentTool> tools() {
        return List.of(readFile(), listDir(), writeFile(), editFile(), deletePath(),
                listForms(), readForm(), replaceForm(), applyPatch());
    }
}
