package io.ailink.agentforge.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AgentForge 网页端入口：Spring Boot（Jetty）+ agent-core。
 *
 * <pre>
 *   java --enable-native-access=ALL-UNNAMED -jar agentforge-web.jar \
 *        --agentforge.web.root=/path/to/project --server.port=8787
 * </pre>
 *
 * 缺省只听 127.0.0.1（agent 能跑命令、改文件）；要对外开放就给 {@code --server.address=0.0.0.0}，这时必须带访问令牌
 * （{@code --agentforge.web.token=…}，不给就随机生成一个打在日志里）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WebApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
