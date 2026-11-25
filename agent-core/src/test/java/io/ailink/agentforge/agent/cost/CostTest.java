package io.ailink.agentforge.agent.cost;

import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.models.ModelFacts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 移植自 coxswain tests/agent-cost-test.ss。 */
class CostTest {

    static ModelFacts price(Double in, Double out, Double cr, Double cw) {
        return ModelFacts.empty().withCost(in, out, cr, cw);
    }

    static final ModelFacts SONNET = price(3.0, 15.0, 0.3, 3.75);
    static final ModelFacts GPT5 = price(1.25, 10.0, 0.125, null);
    static final ModelFacts BARE = price(1.0, 2.0, null, null);
    static final ModelFacts NOCOST = ModelFacts.empty().withName("谁知道").withLimits(100000L, null);

    static void close(double expected, Double got) {
        assertNotNull(got);
        assertEquals(expected, got, 1e-9);
    }

    @Test
    void 两家的记账口径() {
        close(0.010575, Cost.profileCost(Cost.usageProfile(Map.of("input_tokens", 1000, "cache_read_input_tokens", 9000,
                "cache_creation_input_tokens", 500, "output_tokens", 200)), SONNET));
        // OpenAI 形：缓存那部分从 input 里减出来
        CostProfile oai = Cost.usageProfile(Map.of("input_tokens", 10000, "cached_tokens", 8000, "output_tokens", 500));
        close(0.0085, Cost.profileCost(oai, GPT5));
        assertNotEquals((10000 * 1.25 + 8000 * 0.125 + 500 * 10) / 1e6, Cost.profileCost(oai, GPT5), 1e-9);
        // 两个键都在 → 按并列算，不减
        close((1000 * 3 + 1000 * 0.3) / 1e6, Cost.profileCost(Cost.usageProfile(Map.of("input_tokens", 1000,
                "cache_read_input_tokens", 1000, "cached_tokens", 900, "output_tokens", 0)), SONNET));
        // cached > input 也不出负数
        assertTrue(Cost.profileCost(Cost.usageProfile(Map.of("input_tokens", 100, "cached_tokens", 500)), GPT5) >= 0);
    }

    @Test
    void 会话层的Usage已经是Anthropic口径() {
        close(0.010575, Cost.usageCost(new Usage(1000, 200, 9000, 500, 0), SONNET));
        close(0.0, Cost.usageCost(null, BARE));   // 单价知道，只是没花
    }

    @Test
    void 没单价就是不知道() {
        assertNull(Cost.usageCost(new Usage(1000, 100, 0, 0, 0), NOCOST));
        assertNull(Cost.usageCost(new Usage(1000, 0, 0, 0, 0), price(3.0, null, null, null)));
        assertNull(Cost.usageCost(new Usage(0, 1000, 0, 0, 0), price(null, 3.0, null, null)));
        assertNull(Cost.usageCost(new Usage(1, 0, 0, 0, 0), null));
        close(0.002, Cost.usageCost(new Usage(0, 1000, 0, 0, 0), BARE));
    }

    @Test
    void 兜底的方向() {
        Cost.Prices p = Cost.perMillion(BARE);
        assertEquals(1.0, p.cacheRead());
        assertEquals(1.0, p.cacheWrite());
        Cost.Prices g = Cost.perMillion(GPT5);
        assertEquals(0.125, g.cacheRead());
        assertEquals(1.25, g.cacheWrite());
        assertNull(Cost.perMillion(NOCOST));
        Usage u = new Usage(1000, 0, 100000, 0, 0);
        assertTrue(Cost.usageCost(u, BARE) > Cost.usageCost(u, price(1.0, 2.0, 0.1, null)));
    }

    @Test
    void 显示() {
        assertEquals("≈$1.23", Cost.format(1.234));
        assertEquals("≈$0.123", Cost.format(0.1234));
        assertEquals("≈$0.0034", Cost.format(0.00341));
        assertEquals("≈$0.0035", Cost.format(69.0 / 20000), "半数往上进");
        assertEquals("≈$0.0034", Cost.format(0.00344));
        assertEquals("<$0.0001", Cost.format(0.00000012));
        assertEquals("≈$0", Cost.format(0.0));
        assertNull(Cost.format(null));
        assertNull(Cost.format(-1.0));
        assertEquals("≈$12.35", Cost.format(12.3456));
        assertEquals("≈$2.00", Cost.format(2.0));
        assertEquals("≈$0.050", Cost.format(0.05));
    }

    @Test
    void 标准画像() {
        CostProfile p = Cost.standardProfile();
        assertEquals(100000, p.input(), 1e-6);
        assertEquals(900000, p.cacheRead(), 1e-6);
        assertEquals(2000000, p.output(), 1e-6);
        assertEquals(0, p.cacheWrite());
        close(30.57, Cost.modelPrice(SONNET));
        close(20.2375, Cost.modelPrice(GPT5));
        assertNull(Cost.modelPrice(NOCOST));
        ModelFacts withCr = price(10.0, 1.0, 1.0, null), without = price(10.0, 1.0, null, null);
        close(8.1, Cost.modelPrice(without) - Cost.modelPrice(withCr));
        CostProfile cold = Cost.evaluationProfile(1e6, 2, 0);
        assertEquals(1e6, cold.input(), 1e-6);
        assertEquals(0, cold.cacheRead(), 1e-6);
        assertEquals(100000, Cost.evaluationProfile(1e6, 0.1, 0.9).output(), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> Cost.evaluationProfile(1e6, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> Cost.evaluationProfile(1e6, 2, -1));
        assertThrows(IllegalArgumentException.class, () -> Cost.evaluationProfile(1e6, -1, 0.9));
        assertThrows(IllegalArgumentException.class, () -> Cost.evaluationProfile(0, 2, 0.9));
    }

    @Test
    void 比价() {
        CostProfile out = new CostProfile(0, 0, 0, 1000);
        close(2.0 / 15.0, Cost.costRatio(out, SONNET, BARE));
        close(15.0 / 2.0, Cost.costRatio(out, BARE, SONNET));
        ModelFacts cheapOut = price(10.0, 1.0, null, null), priceyOut = price(2.0, 2.0, null, null);
        assertTrue(Cost.costRatio(new CostProfile(0, 0, 0, 10000), priceyOut, cheapOut) < 1, "输出重的活");
        assertTrue(Cost.costRatio(new CostProfile(10000, 0, 0, 0), priceyOut, cheapOut) > 1, "输入重的活");
        assertTrue(Cost.costRatio(new CostProfile(1000, 100000, 0, 0), price(1.0, 1.0, null, null), price(2.0, 1.0, 0.05, null)) < 1);
        assertNull(Cost.costRatio(new CostProfile(0, 0, 0, 100), SONNET, NOCOST));
        assertNull(Cost.costRatio(CostProfile.ZERO, SONNET, BARE), "基准是 0 → null");
    }

    @Test
    void 排序() {
        Cost.Ranking r = Cost.rankByCost(new CostProfile(1e6, 0, 0, 0), List.of(
                new Candidate("贵", SONNET), new Candidate("不知道", NOCOST), new Candidate("便宜", BARE), new Candidate("中", GPT5)));
        assertEquals(List.of("便宜", "中", "贵"), r.ranked().stream().map(Cost.Priced::ref).toList());
        close(1.0, r.ranked().get(0).price());
        assertEquals(List.of("不知道"), r.unknown());
        Cost.Ranking same = Cost.rankByCost(new CostProfile(0, 0, 0, 1000),
                List.of(new Candidate("a", BARE), new Candidate("b", BARE), new Candidate("c", BARE)));
        assertEquals(List.of("a", "b", "c"), same.ranked().stream().map(Cost.Priced::ref).toList(), "同价保持原序");
        Cost.Ranking empty = Cost.rankByCost(CostProfile.ZERO, List.of());
        assertTrue(empty.ranked().isEmpty() && empty.unknown().isEmpty());
    }

    @Test
    void 分项() {
        Cost.Breakdown b = Cost.usageCostBreakdown(new Usage(1000, 200, 9000, 500, 0), SONNET);
        close(b.total(), b.input() + b.cacheRead() + b.cacheWrite() + b.output());
        close(0.003, b.input());
        close(0.0027, b.cacheRead());
        close(0.001875, b.cacheWrite());
        close(0.003, b.output());
    }
}
