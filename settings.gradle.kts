rootProject.name = "agentforge"

// 第一阶段：核心（bitcask / sandbox / agent-core）+ 现有 CLI 接上。
// 后续阶段：app-cli 重写（参照 coxswain main）→ app-gui（Compose）→ app-web（Spring Boot + React）。
include("bitcask", "sandbox", "agent-core", "app-cli", "tui", "tui-agent")
