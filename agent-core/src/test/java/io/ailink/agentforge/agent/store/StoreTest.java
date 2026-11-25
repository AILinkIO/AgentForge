package io.ailink.agentforge.agent.store;

import io.ailink.agentforge.agent.context.Compaction;
import io.ailink.agentforge.agent.context.Reminder;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.bitcask.BitcaskRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 存储契约：同一套用例跑内存实现与 bitcask 实现（libbitcask 装不上时 bitcask 那一份跳过）。 */
class StoreTest {

    private final List<SessionStore> opened = new ArrayList<>();
    private final List<Path> dirs = new ArrayList<>();

    /** 开一个库；bitcask 的放在一个用完即删的临时目录里。 */
    SessionStore open(String kind) {
        SessionStore st = switch (kind) {
            case "memory" -> new InMemorySessionStore();
            case "bitcask" -> {
                Assumptions.assumeTrue(BitcaskRuntime.unavailableReason() == null, "libbitcask 装不上：" + BitcaskRuntime.unavailableReason());
                yield BitcaskSessionStore.open(newDir());
            }
            default -> throw new IllegalArgumentException(kind);
        };
        opened.add(st);
        return st;
    }

    Path newDir() {
        try {
            Path d = Files.createTempDirectory("af-store-test-");
            dirs.add(d);
            return d;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void cleanup() throws IOException {
        for (SessionStore st : opened) {
            try { st.close(); } catch (RuntimeException ignored) { }
        }
        for (Path d : dirs) {
            try (var w = Files.walk(d)) {
                for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void codecRoundTripsEverything() {
        var in = AgentTool.JSON.createObjectNode().put("path", "a.txt");
        in.putArray("xs").add(1).add("b");
        List<Message> ms = List.of(
                Message.user("你好"),
                new Message(Role.ASSISTANT, List.of(new Block.Thinking("想", "sig==", false), new Block.Thinking("enc", null, true),
                        new Block.Text("看看"), new Block.ToolUse("t1", "read-file", in))),
                Message.user(List.of(new Block.ToolResult("t1", List.of(new Block.Text("x"), new Block.Image("image/png", "AAAA")), true),
                        new Block.Document("application/pdf", "JVBE"))),
                Message.assistant("好").withMeta("compaction", "summary").withMeta("n", 3L).withMeta("obj", new Object()));
        List<Message> back = MessageCodec.listFromJson(MessageCodec.toJson(ms));
        assertEquals(ms.get(0), back.get(0));
        assertEquals(ms.get(1), back.get(1), "thinking 签名与 tool_use 一个不少");
        assertEquals(ms.get(2), back.get(2));
        assertEquals("summary", back.get(3).meta("compaction"));
        assertEquals(3L, back.get(3).meta("n"));
        assertNull(back.get(3).meta("obj"), "别的类型只活在进程里");
        assertNull(MessageCodec.decodeBlock(AgentTool.JSON.createObjectNode().put("type", "future")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "bitcask"})
    void syncAppendsOnlyTheTailAndRefusesShorterHistory(String kind) {
        SessionStore st = open(kind);
        long c = st.newConversation("t");
        assertEquals(2, st.sync(c, List.of(Message.user("a"), Message.assistant("b"))));
        assertEquals(0, st.sync(c, List.of(Message.user("a"), Message.assistant("b"))));
        assertEquals(1, st.sync(c, List.of(Message.user("a"), Message.assistant("b"), Message.user("c"))));
        assertThrows(IllegalStateException.class, () -> st.sync(c, List.of(Message.user("a"))));
        assertEquals(3, st.messages(c).size());
        assertEquals(3, st.conversation(c).orElseThrow().messages());
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "bitcask"})
    void searchIndexesShownTextOnly(String kind) {
        SessionStore st = open(kind);
        long c = st.newConversation("天气");
        st.append(c, List.of(Message.user(List.of(new Block.Text("北京今天的天气怎么样"), Reminder.block("秘密文件内容 zzz"))),
                Message.user(List.of(Block.ToolResult.text("t", "工具结果里的 zzz", false))),
                Message.assistant("晴天，适合出门")));
        assertEquals(1, st.search("天气怎么", 10).size());
        assertEquals("user", st.search("天气怎么", 10).getFirst().role());
        assertTrue(st.search("zzz", 10).isEmpty(), "提醒块与工具结果不进索引");
        assertThrows(IllegalArgumentException.class, () -> st.search("天气", 10), "短查询报错而不是回空");
        assertEquals(1, st.search("适合出门", 10).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "bitcask"})
    void conversationsChildrenDeleteTruncate(String kind) {
        SessionStore st = open(kind);
        long a = st.newConversation("a");
        long b = st.newConversation("b");
        long child = st.newConversation("sub", a, "tool-1");
        st.append(a, List.of(Message.user("x")));
        assertEquals(List.of(a, b), st.conversations(0).stream().map(SessionStore.Conversation::id).toList(), "最近更新的在前；子会话不列");
        assertEquals(List.of(child), st.children(a).stream().map(SessionStore.Conversation::id).toList());
        st.addCompaction(a, new Compaction.Pointer(4, 2));
        assertEquals(List.of(new Compaction.Pointer(4, 2)), st.compactions(a));
        assertEquals(1, st.truncate(1));
        assertTrue(st.conversation(b).isEmpty());
        st.deleteConversation(a);
        assertTrue(st.conversation(child).isEmpty(), "子会话一并删掉");
        st.setTitle(st.newConversation(""), "新标题");
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "bitcask"})
    void searchIsSubstringCaseInsensitiveNewestFirst(String kind) {
        SessionStore st = open(kind);
        long a = st.newConversation("旧");
        st.append(a, List.of(Message.user("Hello World from AgentForge"), Message.assistant("北京今天的天气怎么样")));
        long b = st.newConversation("新");
        st.append(b, List.of(Message.user("say HELLO again"), Message.assistant("天气怎么样都行")));
        List<SessionStore.SearchHit> hs = st.search("ello", 0);
        assertEquals(List.of(b, a), hs.stream().map(SessionStore.SearchHit::conversation).toList(), "词内子串也算；新的在前");
        assertEquals(List.of(1, 1), st.search("天气怎么", 0).stream().map(SessionStore.SearchHit::seq).toList());
        assertEquals(b, st.search("天气怎么", 1).getFirst().conversation(), "limit 取最新的");
        assertEquals("新", st.search("天气怎么", 1).getFirst().title());
        assertEquals("assistant", st.search("天气怎么", 1).getFirst().role());
        assertTrue(st.search("foo(bar", 0).isEmpty(), "用户随手输入的符号不让检索崩掉");
        st.deleteConversation(b);
        assertEquals(List.of(a), st.search("天气怎么", 0).stream().map(SessionStore.SearchHit::conversation).toList(), "删了就搜不到");
    }

    @Test
    void bitcaskSurvivesReopenAndNeverReusesIds() {
        Assumptions.assumeTrue(BitcaskRuntime.unavailableReason() == null, "libbitcask 装不上");
        Path dir = newDir();
        var in = AgentTool.JSON.createObjectNode().put("path", "a.txt");
        List<Message> h = List.of(Message.user("第一句话在这里"),
                new Message(Role.ASSISTANT, List.of(new Block.Thinking("想", "sig==", false), new Block.ToolUse("t1", "read-file", in))),
                Message.user(List.of(Block.ToolResult.text("t1", "内容", false))));
        long a, b;
        try (BitcaskSessionStore st = BitcaskSessionStore.open(dir)) {
            a = st.newConversation("甲");
            st.sync(a, h);
            st.addCompaction(a, new Compaction.Pointer(1, 0));
            b = st.newConversation("乙", a, "tool-9");
        }
        try (BitcaskSessionStore st = BitcaskSessionStore.open(dir)) {
            assertEquals(h, st.messages(a), "块原样读回（签名、tool_use id 一个不少）");
            assertEquals(3, st.conversation(a).orElseThrow().messages());
            assertEquals(List.of(new Compaction.Pointer(1, 0)), st.compactions(a));
            SessionStore.Conversation ch = st.children(a).getFirst();
            assertEquals(b, ch.id());
            assertEquals("tool-9", ch.sid());
            assertEquals(a, ch.parent());
            assertEquals(1, st.search("第一句话", 0).size());
            st.deleteConversation(a);
            assertTrue(st.conversation(b).isEmpty());
            long c = st.newConversation("丙");
            assertTrue(c > b, "id 不复用（孤儿索引项不会撞上新对话）");
            assertTrue(st.search("第一句话", 0).isEmpty());
        }
    }
}
