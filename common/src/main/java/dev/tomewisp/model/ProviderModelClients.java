package dev.tomewisp.model;

import com.google.gson.Gson;
import dev.tomewisp.model.anthropic.AnthropicMessagesClient;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.model.openai.OpenAiChatClient;
import java.util.Objects;

/** One protocol-neutral construction boundary shared by Guide and settings probes. */
public final class ProviderModelClients {
    private ProviderModelClients() {}

    public static ModelClient create(ModelConfig config, Gson gson) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(gson, "gson");
        return switch (config.protocol()) {
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesClient(config, gson);
            case OPENAI_CHAT -> new OpenAiChatClient(config, gson);
        };
    }
}
