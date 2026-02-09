package io.ailink.agentforge.template;

import com.hubspot.jinjava.Jinjava;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptRendererTest {

    private final PromptRenderer renderer = new PromptRenderer(new Jinjava());

    @Test
    void renderTranslatorTemplate() {
        Map<String, Object> context = Map.of("role", "翻译助手", "lang", "英文");
        String result = renderer.render("translator.md", context);
        assertEquals("你是一个翻译助手，请翻译为英文。", result);
    }
}
