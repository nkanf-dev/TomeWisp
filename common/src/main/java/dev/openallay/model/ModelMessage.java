package dev.openallay.model;

import java.util.List;
import java.util.Objects;

public record ModelMessage(ModelRole role, List<ModelContent> content) {
    public ModelMessage {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(content);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Model message content must not be empty");
        }
    }

    public static ModelMessage userText(String text) {
        return new ModelMessage(ModelRole.USER, List.of(new ModelContent.Text(text)));
    }
}
