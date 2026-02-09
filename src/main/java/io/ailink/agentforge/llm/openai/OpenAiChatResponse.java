package io.ailink.agentforge.llm.openai;

import io.ailink.agentforge.llm.ChatResponse;
import io.ailink.agentforge.llm.TokenUsage;
import io.ailink.agentforge.llm.openai.dto.OpenAiResponse;

public class OpenAiChatResponse implements ChatResponse<OpenAiResponse> {

    private final OpenAiResponse raw;

    public OpenAiChatResponse(OpenAiResponse raw) {
        this.raw = raw;
    }

    @Override
    public String id() {
        return raw.id();
    }

    @Override
    public String content() {
        if (raw.choices() != null && !raw.choices().isEmpty()) {
            var message = raw.choices().getFirst().message();
            return message != null ? message.content() : "";
        }
        return "";
    }

    @Override
    public String model() {
        return raw.model();
    }

    @Override
    public String stopReason() {
        if (raw.choices() != null && !raw.choices().isEmpty()) {
            return raw.choices().getFirst().finishReason();
        }
        return null;
    }

    @Override
    public TokenUsage usage() {
        return raw.usage() != null
                ? new TokenUsage(raw.usage().promptTokens(), raw.usage().completionTokens())
                : null;
    }

    @Override
    public OpenAiResponse rawResponse() {
        return raw;
    }
}
