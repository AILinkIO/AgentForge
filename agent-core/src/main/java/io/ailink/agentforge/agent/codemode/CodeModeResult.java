package io.ailink.agentforge.agent.codemode;

import java.util.List;

/**
 * 一次脚本运行的结果。
 *
 * @param ok    跑成了
 * @param value 最后一个表达式的值（字符串原样，其余 write 成串；已按输出上限截断）；出错时为 null
 * @param logs  {@code (log! …)} 记的行
 * @param calls 调过的工具名，按序（出错时也给 —— 宿主审计用）
 * @param error 一行诊断（脚本里的错、超时、超次数…）；ok 时为 null
 */
public record CodeModeResult(boolean ok, String value, List<String> logs, List<String> calls, String error) { }
