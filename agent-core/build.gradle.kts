// agentforge-agent-core：agent 业务层（coxswain ion 分支 coxn/agent 的 Java 移植），
// L1 用 Spring AI（ChatModel / ToolCallback），存储用 bitcask，命令执行可走 sandbox。
dependencies {
    "api"(project(":bitcask"))
    "api"(project(":sandbox"))
    "api"(platform(libs.spring.ai.bom))
    "api"(libs.spring.ai.model)
    "api"(libs.spring.ai.client.chat)
    "api"(libs.spring.ai.anthropic)
    "api"(libs.spring.ai.openai)
    "api"(libs.jackson.databind)
    "implementation"(libs.slf4j.api)
    "implementation"(libs.flexmark)
    "implementation"(libs.flexmark.yaml)
}
