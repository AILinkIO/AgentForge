package io.ailink.agentforge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class AppRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AppRunner.class);

    @Override
    public void run(String... args) {
        log.info("AgentForge started successfully.");
    }
}
