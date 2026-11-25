// agentforge-sandbox：keelson clj-sandbox 的 Java 重写（bwrap / landlock / seccomp / Windows AppContainer / 网络代理）。
dependencies {
    "api"(libs.jackson.databind)
    "implementation"(libs.slf4j.api)
}

// 原生件（Landlock + seccomp + 代理桥的 LD_PRELOAD 库）：只在 Linux 上编，直接调 cc。
val nativeOut = layout.buildDirectory.dir("native")
val isLinux = System.getProperty("os.name").lowercase().contains("linux")
val buildNative by tasks.registering(Exec::class) {
    description = "编译 libagentforge_sandbox_native.so（Landlock / seccomp / 代理桥）"
    onlyIf { isLinux }
    val src = file("native/src/ks_sandbox.c")
    inputs.file(src)
    outputs.dir(nativeOut)
    doFirst { nativeOut.get().asFile.mkdirs() }
    commandLine("cc", "-std=c11", "-O2", "-shared", "-fPIC", "-fvisibility=hidden", "-Wall", "-Wextra",
        "-o", nativeOut.get().file("libagentforge_sandbox_native.so").asFile.absolutePath, src.absolutePath)
}

tasks.named<Test>("test") {
    if (isLinux) {
        dependsOn(buildNative)
        systemProperty("agentforge.sandbox.native.dir", nativeOut.get().asFile.absolutePath)
    }
}
