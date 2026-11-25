// agentforge-cli：命令行入口（Spring Boot 无 web 容器 + picocli + JLine）。
plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    "implementation"(project(":agent-core"))
    "implementation"(project(":tui-agent"))
    "implementation"(project(":bitcask"))
    "implementation"(platform(libs.spring.ai.bom))
    "implementation"(libs.spring.boot.starter)
    "implementation"(libs.spring.ai.starter.anthropic)
    "implementation"(libs.spring.ai.starter.openai)
    "implementation"(libs.jinjava)
    "implementation"(libs.flexmark)
    "implementation"(libs.flexmark.yaml)
    "implementation"(libs.flexmark.tables)
    "implementation"(libs.picocli)
    "implementation"(libs.jline)
    "implementation"(libs.javaluator)
    "testImplementation"(libs.spring.boot.starter.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("agentforge.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    standardInput = System.`in`
}

// 把 libbitcask 打进 jar：./gradlew :app-cli:bootJar -Pbitcask.lib=/path/libbitcask.so
// （运行时由 BitcaskLibrary 从 classpath 的 /native/<rid>/ 解出来；不打也行，用 AGENTFORGE_BITCASK_LIB 指过去）
val bitcaskLib = providers.gradleProperty("bitcask.lib")
val bitcaskRid = providers.gradleProperty("bitcask.rid").orElse("linux-x64")
tasks.named<ProcessResources>("processResources") {
    if (bitcaskLib.isPresent) {
        from(bitcaskLib) {
            into(bitcaskRid.map { "native/$it" })
            rename { System.mapLibraryName("bitcask") }
        }
    }
}
