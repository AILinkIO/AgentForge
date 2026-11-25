// agentforge-bitcask：keelson clj-bitcask 的 Java 重写，经 FFM 加载 libbitcask。
// 只依赖 JDK；不依赖 Spring，GUI / CLI / Web 都能直接用。
dependencies {
    "api"(libs.jackson.databind)
}

// openCypher TCK（开发工具，缺省不跑）：-Ptck[=features 目录] [-Ptck.filter=子串,子串]
tasks.withType<Test>().configureEach {
    (findProperty("tck") as String?)?.let { systemProperty("agentforge.tck.dir", it) }
    (findProperty("tck.filter") as String?)?.let { systemProperty("agentforge.tck.filter", it) }
    maxHeapSize = "2g"
}
