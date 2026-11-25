package io.ailink.agentforge.template;

import com.hubspot.jinjava.Jinjava;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class PromptRenderer {

    private final Jinjava jinjava;

    public PromptRenderer(Jinjava jinjava) {
        this.jinjava = jinjava;
    }

    public String render(String templatePath, Map<String, Object> context) {
        String template = loadTemplate(templatePath);
        return jinjava.render(template, context);
    }

    private String loadTemplate(String templatePath) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + templatePath);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt template: " + templatePath, e);
        }
    }
}
