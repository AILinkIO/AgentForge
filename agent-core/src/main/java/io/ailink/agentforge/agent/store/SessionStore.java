package io.ailink.agentforge.agent.store;

import io.ailink.agentforge.agent.context.Compaction;
import io.ailink.agentforge.agent.model.Message;

import java.util.List;
import java.util.Optional;

/**
 * 对话存盘 + 全文检索（coxswain {@code store.ss} + {@code history.ss} 的机制那半）。
 *
 * <h2>设计成能落到 KV + 全文索引上</h2>
 * 每样东西都有一个自然键：对话 {@code conv/<id>}、消息 {@code msg/<conv>/<seq>}（seq 从 0 连续）、压缩指针
 * {@code cmp/<conv>/<at>}、审批规则 {@code perm/<root>/<tool>/<pattern>}；全文索引只收**文本块**（跳过 thinking、
 * 工具结果、系统提醒）—— 签名那种 base64 是纯噪声，工具结果可以是一整个文件，塞进去索引会比对话本身大一个数量级。
 * **能搜到的 ≠ 存下来的**，这是故意的。bitcask 实现只需照这些键存值。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>{@link #sync}：把还没存的那截追加上去。历史**变短**直接报错 —— 要么传错了对话，要么调用方自己截断过，
 *       两种都不该悄悄接着写（会话的压缩是只追加的，见 {@link Compaction}；reset 之后要开新对话）。</li>
 *   <li>{@link #search}：查询短于 {@link #SEARCH_MIN_CHARS} 个字符**报错**而不是回空 —— 回空会让人得出"没说过"的结论。</li>
 *   <li>{@code parent} 是父对话在库里的 id（不是内存里的会话 id —— 那条编号线不出进程）。</li>
 *   <li>落库失败绝不能拦住对话：调用方（runtime）全都兜住，只记一句最后的错。</li>
 * </ul>
 * 线程安全由实现保证。
 */
public interface SessionStore extends AutoCloseable {

    int SEARCH_MIN_CHARS = 3;

    /** 一段对话。{@code sid} 是建它时那段会话的 id（子会话 = 那次 task 调用的 tool_use_id）。 */
    record Conversation(long id, String title, long created, long updated, Long parent, String sid, int messages) { }

    /** 一条命中：哪段对话的第几条、谁说的、命中附近的片段。 */
    record SearchHit(long conversation, int seq, String role, String snippet, String title) { }

    long newConversation(String title, Long parent, String sid);

    default long newConversation(String title) { return newConversation(title, null, null); }

    void setTitle(long conv, String title);

    /** 删掉对话（消息、指针、索引一起）；它的子会话一并删掉。 */
    void deleteConversation(long conv);

    /** 主对话（parent 为空），最近更新的在前；{@code limit <= 0} = 全部。 */
    List<Conversation> conversations(int limit);

    List<Conversation> children(long conv);

    Optional<Conversation> conversation(long conv);

    /** 无条件追加。 */
    void append(long conv, List<Message> msgs);

    /**
     * 让库里这段 = {@code history}：追加库里还没有的那截，回追加了几条。
     *
     * @throws IllegalStateException history 比库里短
     */
    int sync(long conv, List<Message> history);

    List<Message> messages(long conv);

    List<Compaction.Pointer> compactions(long conv);

    void addCompaction(long conv, Compaction.Pointer p);

    /** 只留最近更新的 {@code keep} 段主对话（连同它们的子会话），回删了几段。 */
    int truncate(int keep);

    /**
     * 全文检索（子串，大小写不敏感），新的在前。
     *
     * @throws IllegalArgumentException 查询（去掉首尾空白）短于 {@link #SEARCH_MIN_CHARS}
     */
    List<SearchHit> search(String query, int limit);

    @Override
    default void close() { }

    /** 检查查询长度（实现共用）。 */
    static String checkQuery(String q) {
        String t = q == null ? "" : q.strip();
        if (t.codePointCount(0, t.length()) < SEARCH_MIN_CHARS) {
            throw new IllegalArgumentException("搜索词至少要 " + SEARCH_MIN_CHARS + " 个字（短了索引搜不出来，会误以为没说过）：" + q);
        }
        return t;
    }
}
