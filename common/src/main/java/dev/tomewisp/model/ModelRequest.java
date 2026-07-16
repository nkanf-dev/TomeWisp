package dev.tomewisp.model;

import java.util.List;

public record ModelRequest(
        String systemPrompt,
        List<ModelMessage> messages,
        List<ModelToolDefinition> tools,
        boolean stream,
        String sessionKey) {
    public ModelRequest {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("System prompt must not be blank");
        }
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("At least one model message is required");
        }
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("Model request sessionKey must not be blank");
        }
    }

    public ModelRequest(
            String systemPrompt,
            List<ModelMessage> messages,
            List<ModelToolDefinition> tools,
            boolean stream) {
        this(systemPrompt, messages, tools, stream, "default");
    }
}
