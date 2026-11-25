package io.ailink.agentforge.agent.skill;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.util.Fmt;

import java.util.List;

/**
 * {@code skill} 工具：把一份操作手册装进对话（coxswain {@code tools/skill.ss}）。
 *
 * <p>一直在工具表里，哪怕一个 skill 都没有：在不在随机器变的话"默认给哪几件"就不再一眼看得全；
 * 靠提示词里的目录决定模型会不会调它。每次调用现扫目录（刚写好的 skill 不用重开会话）。
 * 只读、不 sensitive、可并发：能读到的只有某个 skill 目录下那份 SKILL.md，路径由扫描决定，模型只给名字。
 */
public final class SkillTool {
    private SkillTool() { }

    public static List<AgentTool> tools() { return List.of(tool()); }

    public static AgentTool tool() {
        return AgentTool.builder("skill")
                .description("装载一份 skill（系统提示里「可用的 skill」列出来的那些操作手册）。"
                        + "手上的活对上其中一条时先装它再动手：装进来的是整份步骤、基目录，"
                        + "以及旁边有哪些脚本 / 参考文件（相对路径都相对于基目录；基目录多半在"
                        + "工作根目录外面，看那些文件用 run-command 的 cat）。"
                        + "name 要与列表里的一字不差。")
                .required("name", "string", "skill 的名字，与系统提示里列的一字不差")
                .readOnly().concurrencySafe()
                .handler((env, a) -> {
                    String name = a.requireStr("name");
                    List<Skills.Skill> skills = Skills.scan(Skills.dirs(env.workspace().root()));
                    Skills.Skill sk = Skills.find(skills, name);
                    if (sk != null) return "已装载 skill " + name + "\n" + Skills.load(sk);   // 首行是摘要
                    if (skills.isEmpty()) {
                        throw new ToolException("没有叫 " + Fmt.q(name) + " 的 skill —— 这个项目与用户目录下一份 skill 都没有"
                                + "（找过 .agentforge/skills、.claude/skills、~/.claude/skills 等）");
                    }
                    // 认不出当场报错并列出有哪些：静默给个最像的，模型永远学不会拼对
                    throw new ToolException("没有叫 " + Fmt.q(name) + " 的 skill。有的是："
                            + String.join("、", skills.stream().map(Skills.Skill::name).toList()));
                })
                .build();
    }
}
