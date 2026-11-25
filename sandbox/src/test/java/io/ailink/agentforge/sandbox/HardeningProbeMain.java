package io.ailink.agentforge.sandbox;

import io.ailink.agentforge.sandbox.exec.Exec;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.exec.ExecResult;

/** {@link ProcessHardeningTest} 的子 JVM：加固请求后起 sh，打印继承的装载器变量。 */
public final class HardeningProbeMain {
    private HardeningProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        ExecResult r = Exec.run(Exec.localBackend(),
                ProcessHardening.withoutLoaderVariables(ExecRequest.of("/bin/sh", "-c", "echo \"[$LD_KS_HARDENING_PROBE]\"")));
        System.out.print(r.stdout());
        System.out.flush();
        System.exit(r.exitCode());
    }
}
