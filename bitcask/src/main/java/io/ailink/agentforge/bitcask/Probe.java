package io.ailink.agentforge.bitcask;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.ailink.agentforge.bitcask.ffm.BitcaskNative;
import io.ailink.agentforge.bitcask.ffm.Faults;
import io.ailink.agentforge.bitcask.ffm.NativeArena;
import io.ailink.agentforge.bitcask.ffm.NativeTypes;

/**
 * 不开库、不拿锁的目录探测：libbitcask 10.0.0 起走 C 入口 {@code bitcask_meta_read}，这里只做错误码 / 返回形状翻译，
 * 不解析内部字节（格式一变由测试按真库对账发现）。
 */
final class Probe {
    static final String META_FILE = "bitcask.meta";

    private Probe() {
    }

    private static long off(String f) {
        return NativeTypes.off(NativeTypes.META_INFO, f);
    }

    private static BitcaskException corrupt(Object dir, String why) {
        return BitcaskException.of("bitcask/probe-corrupt", String.valueOf(dir), why);
    }

    static ProbeResult probe(Path p) {
        if (p == null || p.toString().isEmpty()) throw new IllegalArgumentException("directory");
        if (!Files.isDirectory(p)) throw BitcaskException.of("bitcask/probe-not-directory", p.toString());
        if (!Files.isRegularFile(p.resolve(META_FILE))) throw BitcaskException.of("bitcask/probe-not-bitcask", p.toString());
        try (var a = new NativeArena()) {
            MemorySegment out = a.pointer();
            MemorySegment fault = a.fault();
            int rc = BitcaskNative.metaRead(a.utf8z(p.toString()), out, fault);
            if (rc != 0) {
                BitcaskException e = Faults.from(rc, fault, "bitcask_meta_read");
                switch (rc) {
                    case 2 -> throw corrupt(p, e.detail());
                    case 18 -> throw BitcaskException.of("bitcask/probe-unsupported", p.toString(), e.detail());
                    default -> throw e;
                }
            }
            MemorySegment ptr = out.get(ValueLayout.ADDRESS, 0);
            MemorySegment info = ptr.reinterpret(NativeTypes.META_INFO.byteSize());
            try {
                return decode(p, info);
            } finally {
                BitcaskNative.metaInfoFree(ptr);
            }
        }
    }

    private static int i32(MemorySegment s, String f) {
        return s.get(ValueLayout.JAVA_INT, off(f));
    }

    private static boolean flag(MemorySegment s, String f) {
        return i32(s, f) != 0;
    }

    private static ProbeResult decode(Path dir, MemorySegment info) {
        boolean index = flag(info, "index_mode");
        int dim = Short.toUnsignedInt(info.get(ValueLayout.JAVA_SHORT, off("vector_dim")));
        int metricRaw = i32(info, "vector_metric"), engineRaw = i32(info, "vector_engine");
        Options.VectorMetric[] metrics = Options.VectorMetric.values();
        Options.VectorEngine[] engines = Options.VectorEngine.values();
        if (metricRaw < 0 || metricRaw >= metrics.length) throw corrupt(dir, "vector metric " + metricRaw);
        if (engineRaw < 0 || engineRaw >= engines.length) throw corrupt(dir, "vector engine " + engineRaw);
        Options.VectorMetric metric = metrics[metricRaw];
        if ((metric == Options.VectorMetric.NONE) != (dim == 0)) throw corrupt(dir, "inconsistent vector config");
        int metaVersion = info.get(ValueLayout.JAVA_BYTE, off("meta_version"));
        int icu = info.get(ValueLayout.JAVA_BYTE, off("icu_major"));
        int unicode = info.get(ValueLayout.JAVA_BYTE, off("unicode_major"));
        if (!index) return new ProbeResult(ProbeResult.Mode.KV, metaVersion, icu, unicode, null, null);
        VectorConfig v = new VectorConfig(dim, metric, flag(info, "vector_quantized"), flag(info, "vector_inmem_int8"), engines[engineRaw]);
        ProbeResult.AnalyzerConfig ac = null;
        if (i32(info, "analyzer_recorded") > 0) {
            int type = i32(info, "analyzer_type");
            Options.Analyzer an = Options.Analyzer.of(type);
            if (an == null) throw corrupt(dir, "analyzer " + type);
            ac = new ProbeResult.AnalyzerConfig(an, i32(info, "analyzer_min_n"), i32(info, "analyzer_max_n"), flag(info, "enable_stop_words"),
                    stopWords(info), i32(info, "min_token_length"), i32(info, "max_token_bytes"), flag(info, "enable_stemming"));
        }
        return new ProbeResult(ProbeResult.Mode.INDEX, metaVersion, icu, unicode, v, ac);
    }

    /** {@code const char* const* stop_words}（NULL 结尾）。 */
    private static List<String> stopWords(MemorySegment info) {
        MemorySegment ptr = info.get(ValueLayout.ADDRESS, off("stop_words"));
        List<String> out = new ArrayList<>();
        if (ptr.equals(MemorySegment.NULL)) return List.of();
        MemorySegment slots = ptr.reinterpret(Long.MAX_VALUE);
        for (long i = 0; ; i++) {
            MemorySegment w = slots.getAtIndex(ValueLayout.ADDRESS, i);
            if (w.equals(MemorySegment.NULL)) break;
            out.add(NativeTypes.cString(w));
        }
        return List.copyOf(out);
    }
}
