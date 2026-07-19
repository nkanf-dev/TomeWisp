package dev.openallay.bridge.protocol;

import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRole;
import java.util.List;

public record ServerAgentHistoryMessage(Role role, List<ServerAgentHistoryContent> content) {
    public enum Role {
        USER,
        ASSISTANT
    }

    public ServerAgentHistoryMessage {
        java.util.Objects.requireNonNull(role, "role");
        content = List.copyOf(content);
        if (content.isEmpty()) throw new IllegalArgumentException(
                "Server Agent history content must not be empty");
    }

    public ServerAgentHistoryMessage(Role role, String text) {
        this(role, List.of(new ServerAgentHistoryContent(
                ServerAgentHistoryContent.Kind.TEXT, text, null, null, null, null)));
    }

    public static ServerAgentHistoryMessage from(ModelMessage message) {
        if (message.content().stream().anyMatch(
                dev.openallay.model.ModelContent.Reasoning.class::isInstance)) {
            throw new IllegalArgumentException("Reasoning cannot enter bridge history");
        }
        return new ServerAgentHistoryMessage(
                message.role() == ModelRole.USER ? Role.USER : Role.ASSISTANT,
                message.content().stream().map(ServerAgentHistoryContent::from).toList());
    }

    public ModelMessage toModelMessage() {
        return new ModelMessage(
                role == Role.USER ? ModelRole.USER : ModelRole.ASSISTANT,
                content.stream().map(ServerAgentHistoryContent::toModelContent).toList());
    }
}
