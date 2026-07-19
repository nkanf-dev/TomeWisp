package dev.openallay.agent.context;

import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelToolDefinition;
import java.util.List;

public interface ContextTokenEstimator {
    int estimate(
            String systemPrompt,
            List<ModelMessage> messages,
            List<ModelToolDefinition> tools);
}
