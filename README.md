# AgentForge

AI Agent Framework，基于 Spring Boot 构建的控制台应用。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| JDK | 21 | 运行环境 |
| Spring Boot | 3.4.3 | 应用框架（Console 模式） |
| Spring Data JPA | 3.4.3 | 数据访问层 |
| Hibernate | 6.6.8 | ORM 框架 |
| SQLite | 3.47.2 | 嵌入式数据库 |
| Maven | 3.9+ | 项目构建与依赖管理 |

## 项目结构

```
AgentForge/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/io/ailink/agentforge/
│   │   │   ├── AgentForgeApplication.java        # 启动类
│   │   │   ├── AppRunner.java                    # CommandLineRunner 控制台入口
│   │   │   └── config/
│   │   │       └── DataSourceConfig.java         # DataSource 配置
│   │   └── resources/
│   │       └── application.yml                   # 应用配置
│   └── test/java/io/ailink/agentforge/
│       └── AgentForgeApplicationTests.java
├── data/                                         # SQLite 数据库（自动创建，不纳入版本控制）
├── .gitignore
└── LICENSE
```

## 启动与打包

```bash
# 编译
mvn compile

# 启动
mvn spring-boot:run

# 运行测试
mvn test

# 打包为可执行 JAR
mvn clean package

# 运行 JAR
java -jar target/agentforge-0.1.0-SNAPSHOT.jar
```

## License

[MIT](LICENSE)
