package io.ailink.agentforge.tui;

/**
 * 补全候选。label 显示（也用来过滤）；insert 是接受时插入的文字（null = label）；detail 在右边灰显；
 * kind 是一个字的种类标记（kind-icon 的替身，"f" 函数、"/" 命令、"@" 文件 …）；doc 选中时旁边开文档框；data 随意。
 */
public record CompletionItem(String label, String insert, String detail, String kind, String doc, Object data) {
    public static CompletionItem of(String label) { return new CompletionItem(label, null, null, null, null, null); }
    public static CompletionItem of(String label, String detail) { return new CompletionItem(label, null, detail, null, null, null); }
    public String insertText() { return insert != null ? insert : label; }
}
