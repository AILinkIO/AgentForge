package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 检索库 + 显式 merge 放进同一把尺子：merge 前、merge 后、关掉重开后，向量 top-k 与暴力答案逐个相同，BM25 命中不变
 * （病因在 libbitcask HNSW clone_live，9.0.0 / S50 修）。形状：ngram / cosine / hnsw、2 维、200 篇、key 带 U+001F 分隔、
 * 带 fields，同一批键写四轮（3/4 死），每轮向量不同（固定种子随机）。⚠ 别换成规整形状：那样旧版也绿，判据就钉不住了。
 */
class MergeSearchTest {
    @TempDir
    Path d;

    static final int N = 200, ROUNDS = 4, K = 3;
    static final float[][][] VECS = new float[ROUNDS][N][];
    static final float[][] QUERIES = new float[60][];

    static {
        Random rnd = new Random(42);
        for (int r = 0; r < ROUNDS; r++) for (int i = 0; i < N; i++) VECS[r][i] = new float[]{(float) (rnd.nextDouble() - 0.5), (float) (rnd.nextDouble() - 0.5)};
        for (int i = 0; i < 60; i++) QUERIES[i] = new float[]{(float) (rnd.nextDouble() - 0.5), (float) (rnd.nextDouble() - 0.5)};
    }

    static String docKey(int i) {
        return "para\u001fch1\u001fsec" + (i / 10) + "\u001f" + i + "\u001f0\u001fc" + i;
    }

    static BitcaskIndex openIx(Path d) {
        return BitcaskIndex.open(d, Options.defaults().analyzer(Options.Analyzer.NGRAM).vectorDimension(2)
                .vectorMetric(Options.VectorMetric.COSINE).vectorEngine(Options.VectorEngine.HNSW));
    }

    static void writeRounds(BitcaskIndex ix) {
        for (int r = 0; r < ROUNDS; r++) {
            for (int i = 0; i < N; i++)
                ix.putDocument(docKey(i), Document.builder().text("块" + i + " 正文 token" + i + "x 第" + r + "轮").field("title", "第" + i + "节")
                        .vector(VECS[r][i]).build());
            ix.flush();
        }
    }

    static double cosDist(float[] a, float[] b) {
        return -((a[0] * (double) b[0] + a[1] * (double) b[1]) / (Math.hypot(a[0], a[1]) * Math.hypot(b[0], b[1])));
    }

    static List<String> bruteTopK(float[] q) {
        float[][] last = VECS[ROUNDS - 1];
        return IntStream.range(0, N).boxed().sorted(Comparator.comparingDouble(i -> cosDist(q, last[i]))).limit(K).map(MergeSearchTest::docKey).toList();
    }

    static List<List<String>> vectorShape(BitcaskIndex ix) {
        List<List<String>> out = new ArrayList<>();
        for (float[] q : QUERIES) out.add(TestSupport.keys(ix.searchVector(q, SearchOptions.k(K))));
        return out;
    }

    static List<String> textShape(BitcaskIndex ix) {
        return IntStream.of(0, 7, 42, 123, 199).mapToObj(i -> ix.searchText("token" + i + "x", SearchOptions.k(K)).getFirst().key()).toList();
    }

    @Test
    void vectorAndBm25HitsSurviveMerge() {
        assumeNative();
        List<List<String>> wantVec = new ArrayList<>();
        for (float[] q : QUERIES) wantVec.add(bruteTopK(q));
        List<String> wantText = IntStream.of(0, 7, 42, 123, 199).mapToObj(MergeSearchTest::docKey).toList();
        try (var ix = openIx(d)) {
            writeRounds(ix);
            assertEquals(wantVec, vectorShape(ix), "merge 之前");
            assertEquals(wantText, textShape(ix));
            ix.closeWriteFile();
            MergeAdvice nm = ix.needsMerge();
            assertTrue(nm.needed(), "3/4 死 ⇒ 策略要 merge");
            ix.merge(nm.files());
            assertEquals(wantVec, vectorShape(ix), "⭐ merge 之后向量命中不错位、不缺条");
            assertEquals(wantText, textShape(ix));
            assertEquals("块7 正文 token7x 第" + (ROUNDS - 1) + "轮", ix.getDocument(docKey(7)).text(), "文档本身完好");
        }
        try (var ix = openIx(d)) {
            assertEquals(wantVec, vectorShape(ix), "关掉重开之后");
            assertEquals(wantText, textShape(ix));
        }
    }
}
