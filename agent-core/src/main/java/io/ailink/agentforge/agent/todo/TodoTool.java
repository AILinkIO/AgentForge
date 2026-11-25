package io.ailink.agentforge.agent.todo;

import io.ailink.agentforge.agent.tool.AgentTool;

import java.util.List;

/**
 * todo-write：让模型把计划写下来（coxswain {@code tools/todo.ss}）。
 *
 * <p>只有一件、而且整份替换：模型引用的是序号，而序号会漂（"第 3 条做完了"时手里那份可能是两轮前的）；
 * 整份替换幂等，没有对不上号这回事。
 *
 * <p>不 sensitive（不落盘、不碰用户东西，每条都要点头的话模型会干脆不用）；也不 read-only ——
 * 不给子 agent（几个子 agent 改同一份是最后一个赢，屏幕上那块答的是"主线干到哪了"）。
 */
public final class TodoTool {
    private TodoTool() { }

    public static List<AgentTool> tools() { return List.of(tool()); }

    public static AgentTool tool() {
        return AgentTool.builder("todo-write")
                .description("把你的计划写成一份待办清单，用户能在屏幕上看到进度。"
                        + "**每次都交完整的一份**（这一件是整份替换，不是增量）——"
                        + "开始一步之前把它标成进行中，做完之后**立刻**标成完成再交一次，不要攒到最后一起勾。\n"
                        + "格式是 markdown 复选框，一行一条：\n"
                        + "  - [ ] 还没开始\n"
                        + "  - [>] 正在做（同时只标一条）\n"
                        + "  - [x] 做完了\n"
                        + "什么时候用：三步以上的活、要改好几个文件、用户一次给了好几件事、做的途中冒出新的要求"
                        + "（补成新的一条）。什么时候不用：一两步就完的活、纯问答。\n"
                        + "纪律：只有真做完（含该做的验证）才标 [x]，不是打算做完；卡住了保持 [>] 并加一条写清"
                        + "卡在哪；不再需要的那条直接删掉；每条要具体、可执行；用户给的命令原样抄进去"
                        + "（参数、顺序都别改）。拿不准要不要用就用。")
                .required("items", "string", "整份清单，markdown 复选框，一行一条")
                .handler((env, a) -> {
                    TodoStore store = TodoStore.of(env.workspace());
                    String key = env.sessionId();
                    int n = store.set(key, a.str("items", ""));
                    if (n == 0) {
                        // 认不出时要说清格式：一句"好的"会让模型以为成了，整轮不再管清单
                        return "清单是空的（一条都没认出来）。每一行要写成 `- [ ] 文字` / `- [>] 文字` / `- [x] 文字`。";
                    }
                    TodoStore.Progress p = store.progress(key);
                    return "记下了：" + p.total() + " 条，完成 " + p.done() + " 条。";
                })
                .build();
    }
}
