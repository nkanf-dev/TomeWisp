package dev.tomewisp.model;

import java.util.List;

public record ModelTurn(
        String providerId,
        String model,
        List<ModelContent> content,
        String stopReason,
        ModelUsage usage) {
    public ModelTurn {
        if (providerId == null || providerId.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("Provider and model are required");
        }
        content = List.copyOf(content);
        if (stopReason == null || stopReason.isBlank()) {
            throw new IllegalArgumentException("Stop reason is required");
        }
    }

    public List<ModelContent.ToolUse> toolUses() {
        return content.stream()
                .filter(ModelContent.ToolUse.class::isInstance)
                .map(ModelContent.ToolUse.class::cast)
                .toList();
    }

    public String text() {
        return content.stream()
                .filter(ModelContent.Text.class::isInstance)
                .map(ModelContent.Text.class::cast)
                .map(ModelContent.Text::text)
                .reduce("", String::concat);
    }
}
