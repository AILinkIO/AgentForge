package io.ailink.agentforge.bitcask.graph;

import java.util.Map;

/**
 * 一条边：vid / rank 64 位无符号、etype 32 位无符号；props 是原样字节（入边一侧恒空——属性只在出边一侧存一份），
 * 要属性 map 用 {@link #properties()}。
 */
public record Edge(long src, long etype, long dst, long rank, byte[] props) {
    /** 属性字节 ⇒ map（同 Cypher 的 properties(r)）。 */
    public Map<String, Object> properties() {
        return GraphModel.decodeEdgeProps(props);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Edge e && e.src == src && e.etype == etype && e.dst == dst && e.rank == rank
                && java.util.Arrays.equals(e.props, props);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(src, etype, dst, rank, java.util.Arrays.hashCode(props));
    }

    @Override
    public String toString() {
        return "Edge[" + Long.toUnsignedString(src) + " -" + etype + "-> " + Long.toUnsignedString(dst) + " rank " + rank + "]";
    }
}
