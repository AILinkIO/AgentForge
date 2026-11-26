// agentforge-web：网页端。后端 Spring Boot（Servlet 容器用 Jetty，不用缺省的 Tomcat）包一层 agent-core，
// 前端 React + Vite + shadcn/ui（frontend/），构建产物打进 jar 的 static/。
//
//   ./gradlew :app-web:bootJar -Pbitcask.lib=/path/libbitcask.so      → app-web/build/libs/agentforge-web.jar
//   -Pweb.skipFrontend=true   只构建后端（前端用 npm run dev 单独跑，Vite 把 /api 代理到 8787）
plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    "implementation"(project(":agent-core"))
    "implementation"(libs.spring.boot.starter.webmvc) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    "implementation"(libs.spring.boot.starter.jetty)
    "testImplementation"(libs.spring.boot.starter.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("agentforge-web.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ── 前端 ────────────────────────────────────────────────────────────────
val frontendDir = layout.projectDirectory.dir("frontend")
val frontendOut = layout.buildDirectory.dir("frontend")
val skipFrontend = providers.gradleProperty("web.skipFrontend").map { it != "false" }.orElse(false)
val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"

val npmInstall by tasks.registering(Exec::class) {
    group = "frontend"
    description = "装前端依赖（有 package-lock.json 就 npm ci）"
    onlyIf { !skipFrontend.get() }
    workingDir(frontendDir)
    inputs.files(frontendDir.file("package.json"), frontendDir.file("package-lock.json")).optional()
    outputs.dir(frontendDir.dir("node_modules"))
    commandLine(npm, if (frontendDir.file("package-lock.json").asFile.exists()) "ci" else "install", "--no-audit", "--no-fund")
}

val npmBuild by tasks.registering(Exec::class) {
    group = "frontend"
    description = "构建前端（tsc + vite build）到 build/frontend"
    onlyIf { !skipFrontend.get() }
    dependsOn(npmInstall)
    workingDir(frontendDir)
    inputs.dir(frontendDir.dir("src"))
    inputs.files(frontendDir.file("index.html"), frontendDir.file("vite.config.ts"), frontendDir.file("package.json"),
        frontendDir.file("tsconfig.json"), frontendDir.file("tsconfig.app.json"))
    outputs.dir(frontendOut)
    environment("AGENTFORGE_WEB_OUT", frontendOut.get().asFile.absolutePath)
    commandLine(npm, "run", "build")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(npmBuild)
    from(frontendOut) { into("static") }
}

// 把 libbitcask 打进 jar（同 app-cli）
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
