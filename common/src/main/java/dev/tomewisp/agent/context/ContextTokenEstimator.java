package dev.tomewisp.agent.context;

import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelToolDefinition;
import java.util.List;

public interface ContextTokenEstimator {
    int estimate(
            String systemPrompt,
            List<ModelMessage> messages,
            List<ModelToolDefinition> tools);
}
