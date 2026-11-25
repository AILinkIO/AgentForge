package io.ailink.agentforge.bitcask.cypher;

import java.util.List;

/**
 * 运行期的边值（clj 的 x/Rel）：props 是属性字节（byte[]），或 {@link #UNKNOWN}——入边一侧不存属性，用到时再点查。
 * etype 是 32 位无符号（long）。相等按全部字段（props 按引用，同 clj 的 defrecord 对 byte[] 的语义）。
 */
record VRel(long src, long etype, long dst, long rank, Object props) {
    static final Kw UNKNOWN = Kw.of("unknown");

    /** 边的身份 [src etype dst rank]（clj 的 x/rel-id）。 */
    List<Long> id() {
        return List.of(src, etype, dst, rank);
    }
}
