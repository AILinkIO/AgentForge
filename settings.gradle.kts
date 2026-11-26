rootProject.name = "agentforge"

// 核心（bitcask / sandbox / agent-core）→ CLI（tui / tui-agent / app-cli）→ Web（app-web：Spring Boot + Jetty 后端，React 前端）。
include("bitcask", "sandbox", "agent-core", "app-cli", "tui", "tui-agent", "app-web")
