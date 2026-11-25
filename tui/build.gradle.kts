// agentforge-tui：全屏 TUI 内核（终端层、屏幕 diff、窗口、文档 buffer、键表、命令循环、minibuffer、主题）。
// 移植自 coxswain main 的 Clojure TUI 核心，只保留 agent 工作台要用的部分；只依赖 JLine。
plugins {
    application
}

dependencies {
    "api"(libs.jline)
}

application {
    mainClass.set("io.ailink.agentforge.tui.demo.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
