package io.ailink.agentforge.llm.claude;

import io.ailink.agentforge.llm.AbstractLlmProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.llm.claude")
public class ClaudeProperties extends AbstractLlmProperties<ClaudeProperties> {

    private String messagesPath = "/v1/messages";

    @Override
    public String getApiPath() {
        return messagesPath;
    }

    public String getMessagesPath() {
        return messagesPath;
    }

    public void setMessagesPath(String messagesPath) {
        this.messagesPath = messagesPath;
    }
}
