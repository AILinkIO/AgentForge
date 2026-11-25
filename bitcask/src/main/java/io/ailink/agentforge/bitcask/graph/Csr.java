package io.ailink.agentforge.bitcask.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.ailink.agentforge.bitcask.BitcaskException;

/**
 * 图 OLAP 的进程内 CSR（正向 + 转置），由 {@link Graph#materialize} 物化；之后 PageRank / 连通分量 / SSSP 全在内存数组上迭代，零 KV 访问。
 * 不可变、线程安全。vid 按 64 位无符号排序；没有边的孤点不进，重边去重（简单图语义）。
 */
public final class Csr {
    private final long[] vids;
    private final Map<Long, Integer> index;
    private final int[] xadj, adjncy, xadjR, adjncyR;

    private Csr(long[] vids, Map<Long, Integer> index, int[] xadj, int[] adjncy, int[] xadjR, int[] adjncyR) {
        this.vids = vids;
        this.index = index;
        this.xadj = xadj;
        this.adjncy = adjncy;
        this.xadjR = xadjR;
        this.adjncyR = adjncyR;
    }

    public record RankedVertex(long vid, double rank) {
    }

    public record ComponentOf(long vid, long component) {
    }

    public record Distance(long vid, int distance) {
    }

    /** (i, j) 对（编成 i&lt;&lt;32 | j）排序后 ⇒ [xadj, adjncy]。 */
    private static int[][] arrays(long[] pairs, int n) {
        long[] sorted = Arrays.copyOf(pairs, pairs.length);
        Arrays.sort(sorted);
        int[] xadj = new int[n + 1];
        int[] adj = new int[sorted.length];
        for (long p : sorted) xadj[(int) (p >>> 32) + 1]++;
        for (int i = 1; i <= n; i++) xadj[i] += xadj[i - 1];
        int[] cursor = Arrays.copyOf(xadj, xadj.length);
        for (long p : sorted) {
            int row = (int) (p >>> 32);
            adj[cursor[row]++] = (int) p;
        }
        return new int[][]{xadj, adj};
    }

    private static int sortUnique(long[] a, int len) {
        Arrays.sort(a, 0, len);
        if (len == 0) return 0;
        int w = 1;
        for (int i = 1; i < len; i++) if (a[i] != a[w - 1]) a[w++] = a[i];
        return w;
    }

    /** src[i] → dst[i]（vid 按无符号解释）⇒ 去重 ⇒ 顶点表无符号升序 ⇒ 双向 CSR。 */
    public static Csr build(long[] src, long[] dst) {
        int m0 = src.length;
        if (m0 != dst.length) throw new IllegalArgumentException("src/dst length");
        long[] flipped = new long[2 * m0];
        for (int i = 0; i < m0; i++) {
            flipped[i] = src[i] ^ Long.MIN_VALUE;
            flipped[m0 + i] = dst[i] ^ Long.MIN_VALUE;
        }
        int n = sortUnique(flipped, 2 * m0);
        long[] vids = new long[n];
        Map<Long, Integer> index = new HashMap<>(2 * Math.max(1, n));
        for (int i = 0; i < n; i++) {
            vids[i] = flipped[i] ^ Long.MIN_VALUE;
            index.put(vids[i], i);
        }
        long[] fwd = new long[m0];
        for (int i = 0; i < m0; i++) {
            long a = Arrays.binarySearch(flipped, 0, n, src[i] ^ Long.MIN_VALUE);
            long b = Arrays.binarySearch(flipped, 0, n, dst[i] ^ Long.MIN_VALUE);
            fwd[i] = (a << 32) | b;
        }
        int m = sortUnique(fwd, m0);
        fwd = Arrays.copyOf(fwd, m);
        long[] rev = new long[m];
        for (int i = 0; i < m; i++) rev[i] = ((fwd[i] & 0xFFFFFFFFL) << 32) | (fwd[i] >>> 32);
        int[][] f = arrays(fwd, n), r = arrays(rev, n);
        return new Csr(vids, index, f[0], f[1], r[0], r[1]);
    }

    /** |V|（有边的顶点）。 */
    public int vertexCount() {
        return vids.length;
    }

    /** |E|（去重后）。 */
    public int edgeCount() {
        return adjncy.length;
    }

    /** 顶点表（无符号升序）。 */
    public long[] vertices() {
        return vids.clone();
    }

    /** 出邻居（vid 升序）；未知顶点 ⇒ 空。 */
    public List<Long> outNeighbors(long vid) {
        Integer i = index.get(vid);
        List<Long> out = new ArrayList<>();
        if (i != null) for (int k = xadj[i]; k < xadj[i + 1]; k++) out.add(vids[adjncy[k]]);
        return out;
    }

    public List<RankedVertex> pageRank() {
        return pageRank(0.85, 1e-10, 100);
    }

    /** PageRank：阻尼 + dangling 质量校正，L1 收敛（vid 升序；总和 ≈ 1；空图 ⇒ 空）。 */
    public List<RankedVertex> pageRank(double damping, double epsilon, int maxIterations) {
        if (!(damping >= 0.0 && damping <= 1.0)) throw BitcaskException.of("bitcask/graph-pagerank-damping");
        if (!(epsilon > 0.0)) throw BitcaskException.of("bitcask/graph-pagerank-eps");
        if (maxIterations < 0) throw BitcaskException.of("bitcask/graph-negative", "maxIterations");
        int n = vids.length;
        List<RankedVertex> out = new ArrayList<>();
        if (n == 0) return out;
        double[] r = new double[n];
        Arrays.fill(r, 1.0 / n);
        for (int it = 0; it < maxIterations; it++) {
            double dangling = 0;
            for (int i = 0; i < n; i++) if (xadj[i] == xadj[i + 1]) dangling += r[i];
            double[] nxt = new double[n];
            Arrays.fill(nxt, (1.0 - damping) / n + damping * dangling / n);
            for (int i = 0; i < n; i++) {
                int deg = xadj[i + 1] - xadj[i];
                if (deg > 0) {
                    double share = damping * (r[i] / deg);
                    for (int k = xadj[i]; k < xadj[i + 1]; k++) nxt[adjncy[k]] += share;
                }
            }
            double diff = 0;
            for (int i = 0; i < n; i++) diff += Math.abs(nxt[i] - r[i]);
            r = nxt;
            if (diff < epsilon) break;
        }
        for (int i = 0; i < n; i++) out.add(new RankedVertex(vids[i], r[i]));
        return out;
    }

    /** 弱连通分量（无向最小标号传播；代表 = 分量内最小 vid；vid 升序）。 */
    public List<ComponentOf> components() {
        int n = vids.length;
        int[] labels = new int[n];
        for (int i = 0; i < n; i++) labels[i] = i;
        boolean changed = true;
        while (changed) {
            changed = false;
            int[] nxt = Arrays.copyOf(labels, n);
            for (int i = 0; i < n; i++) {
                int m = labels[i];
                for (int k = xadj[i]; k < xadj[i + 1]; k++) m = Math.min(m, labels[adjncy[k]]);
                for (int k = xadjR[i]; k < xadjR[i + 1]; k++) m = Math.min(m, labels[adjncyR[k]]);
                if (m < labels[i]) {
                    nxt[i] = m;
                    changed = true;
                }
            }
            labels = nxt;
        }
        List<ComponentOf> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new ComponentOf(vids[i], vids[labels[i]]));
        return out;
    }

    /** 单源最短路（无权 = BFS 距离）⇒ 可达顶点（vid 升序）；源不在图里 ⇒ null。 */
    public List<Distance> shortestDistances(long source) {
        Integer s = index.get(source);
        if (s == null) return null;
        int n = vids.length;
        int[] dist = new int[n];
        Arrays.fill(dist, -1);
        dist[s] = 0;
        List<Integer> frontier = List.of(s);
        int depth = 0;
        while (!frontier.isEmpty()) {
            List<Integer> fresh = new ArrayList<>();
            for (int i : frontier) {
                for (int k = xadj[i]; k < xadj[i + 1]; k++) {
                    int j = adjncy[k];
                    if (dist[j] < 0) {
                        dist[j] = depth + 1;
                        fresh.add(j);
                    }
                }
            }
            frontier = fresh;
            depth++;
        }
        List<Distance> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (dist[i] >= 0) out.add(new Distance(vids[i], dist[i]));
        return out;
    }
}
