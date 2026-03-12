package io.ailink.agentforge.llm.openai;

import io.ailink.agentforge.llm.AbstractLlmProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.llm.openai")
public class OpenAiProperties extends AbstractLlmProperties<OpenAiProperties> {

    private String chatPath = "/v1/chat/completions";

    @Override
    public String getApiPath() {
        return chatPath;
    }

    public String getChatPath() {
        return chatPath;
    }

    public void setChatPath(String chatPath) {
        this.chatPath = chatPath;
    }
}
