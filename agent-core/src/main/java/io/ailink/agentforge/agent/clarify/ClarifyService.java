package io.ailink.agentforge.agent.clarify;

import java.util.List;

/**
 * 会话侧"把选择题摆到用户面前"的能力（coxswain clarify 的 {@code 'ask} 过程）。
 *
 * <p>实现方：摆进会话的 pending-question，**阻塞**工具线程直到用户答 / 关掉 / 按停。
 * 同一时刻至多一道题在等（与审批共用那一个槽），所以 clarify 工具不并发安全。
 */
@FunctionalInterface
public interface ClarifyService {

    /** 一个选项。 */
    record Option(String label, String description) { }

    /** 一道题；header 是很短的页签名。 */
    record Question(String question, String header, List<Option> options, boolean multiple) { }

    /** 交给界面的一次提问；toolUseId 让记录认得出"这一问属于哪次工具调用"。 */
    record Request(String toolUseId, List<Question> questions) { }

    /** 用户的回应。 */
    sealed interface Answer permits Answered, Dismissed, Stopped { }

    /** 按题顺序，每题一个选中 label 表（"自己填"的就是那串字）；某题空表 = 没答。 */
    record Answered(List<List<String>> answers) implements Answer { }

    /** 用户关掉了面板。 */
    record Dismissed() implements Answer { }

    /** 用户停掉了这一轮。 */
    record Stopped() implements Answer { }

    Answer ask(Request request) throws InterruptedException;
}
