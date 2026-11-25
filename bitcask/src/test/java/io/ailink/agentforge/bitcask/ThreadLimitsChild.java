package io.ailink.agentforge.bitcask;

import java.nio.file.Path;

/**
 * {@link RuntimeDiagnosticsTest} 的子进程入口：c_api_test.c test_thread_limits_and_tuning 逐格，任何失败写 stderr、非零退出。
 * （线程上限是进程级、首个检索库开库即冻结 ⇒ 独立子 JVM。）
 */
public final class ThreadLimitsChild {
    private static void check(boolean c, String what) {
        if (!c) throw new IllegalStateException("FAIL: " + what);
    }

    public static void main(String[] args) {
        try {
            Path dir = Path.of(args[0]);
            // 冻结前可反复设置
            BitcaskRuntime.setThreadLimits(3, 3);
            BitcaskRuntime.setThreadLimits(2, 2);
            // 建库（首个检索库 ⇒ 冻结 (2, 2)），写 100 篇 + 关库封口出 BM25 段
            Options opts = Options.defaults().analyzer(Options.Analyzer.WHITESPACE).segmentVerifyCrc(true);
            try (var ix = BitcaskIndex.open(dir, opts)) {
                for (int i = 0; i < 100; i++) ix.putDocument("t_" + i, i % 2 == 1 ? "alpha beta" : "alpha gamma");
                ix.flush();
            }
            // 已冻结：同值幂等；异值拒绝，fault 点名生效值
            BitcaskRuntime.setThreadLimits(2, 2);
            try {
                BitcaskRuntime.setThreadLimits(4, 2);
                check(false, "异值没被拒");
            } catch (BitcaskException e) {
                check(e.error() == BitcaskError.INVALID_OPTION, "异值 ⇒ INVALID_OPTION");
                check(e.detail() != null && e.detail().contains("already in effect"), "报文含 already in effect：" + e.detail());
                check(e.detail().contains("index_workers=2"), "报文含 index_workers=2：" + e.detail());
            }
            // 开库调优：校验 / 不校验段 CRC 都能读回同样的命中
            for (Boolean verify : new Boolean[]{true, false, null}) {
                try (var ix = BitcaskIndex.open(dir, opts.copy().segmentVerifyCrc(verify))) {
                    check(ix.searchText("alpha", SearchOptions.k(200)).size() == 100, "verify=" + verify + " ⇒ 100 条");
                    check(ix.searchText("beta", SearchOptions.k(200)).size() == 50, "verify=" + verify + " ⇒ beta 50 条");
                }
            }
            System.out.println("OK");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
