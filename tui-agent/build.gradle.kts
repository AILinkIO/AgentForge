// agentforge-tui-agent：全屏 agent 工作台（transcript + 输入框 + 审批 / 提问面板 + 选择器），
// 建在 :tui（终端内核）与 :agent-core（会话 / 运行时）之上。只做 agent，不做编辑器。
dependencies {
    "api"(project(":tui"))
    "api"(project(":agent-core"))
    // markdown：flexmark 解析成 AST（GFM 表格 / 删除线 / 任务清单 / 裸链接），这边只写 AST ⇒ 终端 face 的渲染
    "implementation"(libs.flexmark)
    "implementation"(libs.flexmark.tables)
    "implementation"(libs.flexmark.strikethrough)
    "implementation"(libs.flexmark.tasklist)
    "implementation"(libs.flexmark.autolink)
    // 代码块语法高亮：借 RSyntaxTextArea 的 TokenMaker（40+ 种语言的词法器，只用分词，不碰 Swing 组件）
    "implementation"(libs.rsyntaxtextarea)
}

// 会话头卡片上的版本号：资源 version.txt 里的 ${version} 在打包时换成项目版本
tasks.withType<ProcessResources>().configureEach {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("**/version.txt") { expand("version" to v) }
}
