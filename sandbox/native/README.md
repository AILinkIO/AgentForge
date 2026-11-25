# sandbox 原生件（libagentforge_sandbox_native）

Landlock（文件系统加固）+ seccomp（网络隔离）+ 代理桥。源码 `src/ks_sandbox.c`，取自 keelson `native/sandbox`（导出面只有 `ks_*`）。

- 入口：`ks_probe`（Landlock ABI 版本）、`ks_apply`（对当前进程套用策略）、`ks_spawn`（fork → 套用 → execve）、
  `ks_write_seccomp`（把过滤器写成 `bwrap --seccomp` 认的格式）。
- 子进程经 `LD_PRELOAD` 装载本库时，构造函数读环境变量 `KS_SANDBOX_POLICY` 指向的 blob 并套用；套不上就退出 127（fail closed）。
  —— 这一条是它必须是原生库、不能用 Java 重写的原因。
- Java 侧：`io.ailink.agentforge.sandbox.ffm.SandboxNative`（FFM 下行调用）、`linux.LandlockPolicyWriter`（策略 blob）。

构建：`./gradlew :sandbox:buildNative` → `sandbox/build/native/libagentforge_sandbox_native.so`（仅 Linux，需要 cc）。
运行时用系统属性 `agentforge.sandbox.native.dir` 指到所在目录。

借鉴 OpenAI Codex CLI（Apache-2.0）：`codex-rs/linux-sandbox/src/seccomp.rs` 与 `linux_run_main.rs` 的 Landlock 规则集。
