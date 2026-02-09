package io.ailink.agentforge.config;

import com.hubspot.jinjava.Jinjava;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemplateConfig {

    @Bean
    public Jinjava jinjava() {
        return new Jinjava();
    }
}
