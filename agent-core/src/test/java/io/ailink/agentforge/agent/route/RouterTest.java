package io.ailink.agentforge.agent.route;

import io.ailink.agentforge.agent.cost.Candidate;
import io.ailink.agentforge.agent.models.ModelFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 移植自 coxswain tests/agent-route-test.ss。 */
class RouterTest {

    static ModelFacts facts(Double in, Double out, long ctx, boolean tools, boolean reasons, boolean attach) {
        return ModelFacts.empty().withCost(in, out, null, null).withLimits(ctx, null).withAbilities(reasons, tools, attach);
    }

    static ModelFacts facts(double in, double out) { return facts(in, out, 400000, true, true, false); }

    static final ModelFacts 贵 = facts(3, 15), 中 = facts(1.25, 10), 便宜 = facts(0.14, 0.28);
    static final ModelFacts 没单价 = facts(null, null, 400000, true, true, true);

    static Candidate c(String ref, ModelFacts f) { return new Candidate(ref, f); }

    Router router;

    @BeforeEach
    void setUp() { router = new Router(); }

    String routed(TaskKind k, Candidate main, Candidate... pool) {
        router.enabled(true);
        router.build(main, List.of(pool));
        return router.model(k, main.ref()).model();
    }

    String note(TaskKind k, Candidate main, Candidate... pool) {
        router.enabled(true);
        router.build(main, List.of(pool));
        return router.model(k, main.ref()).why();
    }

    String routedOff(TaskKind k, Candidate main, Candidate... pool) {
        router.enabled(false);
        router.build(main, List.of(pool));
        return router.model(k, main.ref()).model();
    }

    @Test
    void 省钱路由只往便宜走() {
        assertEquals("便宜", routed(TaskKind.JUNIOR, c("贵", 贵), c("贵", 贵), c("中", 中), c("便宜", 便宜)));
        assertEquals("便宜", routed(TaskKind.JUNIOR, c("便宜", 便宜), c("贵", 贵), c("中", 中), c("便宜", 便宜)));
        assertEquals("便宜", routed(TaskKind.JUNIOR, c("便宜", 便宜), c("贵", 贵), c("中", 中)));
        assertEquals("中", routed(TaskKind.SENIOR, c("中", 中), c("贵", 贵), c("便宜", 便宜)));
        assertEquals("中", routed(TaskKind.DEEP, c("中", 中), c("贵", 贵), c("便宜", 便宜)));
        assertEquals("甲", routed(TaskKind.JUNIOR, c("甲", facts(1, 1)), c("乙", facts(1, 1))), "同价不换");
        assertTrue(note(TaskKind.JUNIOR, c("贵", 贵), c("便宜", 便宜)).contains("不到一成"));
    }

    @Test
    void 能力兜底() {
        ModelFacts 看不了图 = facts(0.1, 0.2, 400000, true, true, false);
        ModelFacts 能看图但贵 = facts(30.0, 60.0, 400000, true, true, true);
        assertEquals("贵但能看图", routed(TaskKind.VISION, c("主", 看不了图), c("主", 看不了图), c("贵但能看图", 能看图但贵)));
        assertTrue(note(TaskKind.VISION, c("主", 看不了图), c("贵但能看图", 能看图但贵)).contains("看得了图"));
        assertTrue(router.table().stream().filter(e -> e.kind() == TaskKind.VISION).findFirst().orElseThrow().fallback());
        assertEquals("便宜也能看图", routed(TaskKind.VISION, c("主", 看不了图), c("贵但能看图", 能看图但贵),
                c("便宜也能看图", facts(1.0, 2.0, 400000, true, true, true))));
        assertEquals("主能看图", routed(TaskKind.VISION, c("主能看图", 能看图但贵), c("主能看图", 能看图但贵),
                c("便宜也能看图", facts(1.0, 2.0, 400000, true, true, true))));
        assertEquals("主", routed(TaskKind.VISION, c("主", 看不了图), c("主", 看不了图)));
        assertTrue(note(TaskKind.VISION, c("主", 看不了图), c("主", 看不了图)).contains("做不成"));
        assertEquals(1, router.problems().size(), "第 4 条：哪类活没着落");
        assertEquals(TaskKind.VISION, router.problems().get(0).kind());

        ModelFacts 小窗口 = facts(0.1, 0.2, 8192, true, true, false), 大窗口便宜 = facts(0.5, 1.0, 1000000, true, true, false);
        assertEquals("大窗口", routed(TaskKind.DEEP, c("主", 小窗口), c("主", 小窗口), c("大窗口", 大窗口便宜)));
        assertEquals("主", routed(TaskKind.DEEP, c("主", facts(30.0, 60.0, 1000000, true, true, false)), c("便宜", 大窗口便宜)));
        assertEquals("主", routed(TaskKind.JUNIOR, c("主", 小窗口), c("主", 小窗口)));
        assertEquals("神秘但能看图", routed(TaskKind.VISION, c("主", facts(1.0, 2.0, 400000, true, true, false)), c("神秘但能看图", 没单价)));
    }

    @Test
    void 不知道价钱就不换() {
        assertEquals("主", routed(TaskKind.JUNIOR, c("主", 没单价), c("便宜", 便宜)));
        assertTrue(note(TaskKind.JUNIOR, c("主", 没单价), c("便宜", 便宜)).contains("没有单价"));
        assertEquals("贵", routed(TaskKind.JUNIOR, c("贵", 贵), c("神秘", 没单价)));
    }

    @Test
    void 硬门槛() {
        assertEquals("贵", routed(TaskKind.JUNIOR, c("贵", 贵), c("不会调工具", facts(0.1, 0.2, 400000, false, true, false))));
        assertEquals("小窗口", routed(TaskKind.JUNIOR, c("贵", 贵), c("小窗口", facts(0.1, 0.2, 8192, true, true, false))));
        assertEquals("会推理", routed(TaskKind.SENIOR, c("主", facts(1.0, 2.0, 400000, true, false, false)),
                c("会推理", facts(5.0, 10.0, 400000, true, true, false))));
        assertEquals(List.of("能看图"), Router.eligible(TaskKind.VISION, List.of(
                c("能看图", facts(1.0, 2.0, 400000, true, true, true)), c("看不了", facts(1.0, 2.0, 400000, true, true, false))))
                .stream().map(Candidate::ref).toList());
    }

    @Test
    void 任务类型() {
        assertEquals(TaskKind.JUNIOR, TaskKind.parse("junior"));
        assertEquals(TaskKind.DEEP, TaskKind.parse("deep"));
        assertEquals(TaskKind.VISION, TaskKind.parse("vision"));
        assertEquals(TaskKind.SENIOR, TaskKind.parse("light"), "老名字不认了");
        assertEquals(TaskKind.SENIOR, TaskKind.parse("junoir"), "拼错了 → senior");
        assertEquals(TaskKind.SENIOR, TaskKind.parse(null));
        assertNull(TaskKind.parseStrict("standard"));
        assertEquals(List.of("junior", "deep", "vision", "senior"), TaskKind.ids());
        assertNotNull(TaskKind.DEEP.minContext());
        assertTrue(TaskKind.VISION.needAttachment());
        assertTrue(TaskKind.SENIOR.needReasoning());
        assertEquals(TaskKind.Tier.CHEAP, TaskKind.JUNIOR.tier());
        assertNotEquals(TaskKind.Tier.CHEAP, TaskKind.SENIOR.tier());
        assertTrue(TaskKind.descriptions().contains("deep＝"));
    }

    @Test
    void 配置指定的模型() {
        router.pin(TaskKind.JUNIOR, "贵");
        assertEquals("贵", routed(TaskKind.JUNIOR, c("便宜", 便宜), c("便宜", 便宜), c("贵", 贵)), "哪怕更贵");
        assertEquals("便宜", routedOff(TaskKind.JUNIOR, c("便宜", 便宜), c("便宜", 便宜), c("贵", 贵)), "关着时钉的不生效");
        router.unpinAll();
        router.pin(TaskKind.JUNIOR, "根本没有这条");
        assertEquals("便宜", routed(TaskKind.JUNIOR, c("便宜", 便宜), c("便宜", 便宜)));
        assertTrue(note(TaskKind.JUNIOR, c("便宜", 便宜), c("便宜", 便宜)).contains("用不了"));
        router.unpinAll();
        router.pin(TaskKind.VISION, "看不了图的");
        assertTrue(note(TaskKind.VISION, c("主", facts(1.0, 2.0, 400000, true, true, true)),
                c("看不了图的", facts(1.0, 2.0, 400000, true, true, false))).contains("干不了"));
        router.unpinAll();
        assertTrue(router.pins().isEmpty());
        router.pin(TaskKind.VISION, "某条");
        assertEquals("某条", router.pins().get(TaskKind.VISION));
        router.unpin(TaskKind.VISION);
        assertTrue(router.pins().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> router.pin(TaskKind.VISION, null));
    }

    @Test
    void 开关() {
        assertFalse(new Router().enabled(), "默认关");
        router.build(c("贵", 贵), List.of(c("便宜", 便宜)));
        assertEquals(TaskKind.values().length, router.table().size(), "关着时表照建");
        Router.Decision d = router.model(TaskKind.JUNIOR, "贵");
        assertEquals("贵", d.model());
        assertTrue(d.why().contains("路由没开"));
        assertEquals("能看图", routedOff(TaskKind.VISION, c("看不了图", facts(1.0, 2.0, 400000, true, true, false)),
                c("能看图", facts(1.0, 2.0, 400000, true, true, true))), "关着时能力兜底照走");
        assertEquals("便宜的能看图", routedOff(TaskKind.VISION, c("看不了图", facts(1.0, 2.0, 400000, true, true, false)),
                c("贵的能看图", facts(3.0, 15.0, 400000, true, true, true)), c("便宜的能看图", facts(0.14, 0.28, 400000, true, true, true))));
        router.clear();
        assertEquals("主", router.model(TaskKind.VISION, "主").model(), "表里没有 → 主模型");
        assertNull(router.model(TaskKind.VISION, null).model());
        router.build(null, List.of());
        assertTrue(router.table().isEmpty());
    }

    @Test
    void 成数() {
        assertEquals("换成它：价钱差不多", Router.howMuchCheaper(0.97));
        assertEquals("换成它：约 2 成价钱", Router.howMuchCheaper(0.194));
        assertEquals("换成它：不到一成价钱", Router.howMuchCheaper(0.02));
    }
}
