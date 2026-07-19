package dev.tomewisp.agent.context;

import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRole;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates and groups model messages without provider-specific assumptions. */
public final class ContextStructure {
    private ContextStructure() {}

    public record Unit(
            int fromIndex,
            int toIndexExclusive,
            boolean toolExchange,
            List<ModelMessage> messages) {
        public Unit {
            if (fromIndex < 0 || toIndexExclusive <= fromIndex) {
                throw new IllegalArgumentException("context unit range is invalid");
            }
            messages = List.copyOf(messages);
            if (messages.size() != toIndexExclusive - fromIndex) {
                throw new IllegalArgumentException("context unit range does not match messages");
            }
        }
    }

    public static List<Unit> units(List<ModelMessage> messages) {
        messages = List.copyOf(messages);
        ArrayList<Unit> units = new ArrayList<>();
        Set<String> seenToolUses = new HashSet<>();
        int index = 0;
        while (index < messages.size()) {
            ModelMessage message = messages.get(index);
            List<ModelContent.ToolUse> uses = toolUses(message);
            List<ModelContent.ToolResult> standaloneResults = toolResults(message);
            if (!standaloneResults.isEmpty()) {
                throw new IllegalArgumentException("orphan tool result at message " + index);
            }
            if (uses.isEmpty()) {
                units.add(new Unit(index, index + 1, false, List.of(message)));
                index++;
                continue;
            }
            if (message.role() != ModelRole.ASSISTANT) {
                throw new IllegalArgumentException("tool use must belong to an assistant message");
            }
            if (index + 1 >= messages.size()) {
                throw new IllegalArgumentException("tool use has no following result message");
            }
            for (ModelContent.ToolUse use : uses) {
                if (!seenToolUses.add(use.id())) {
                    throw new IllegalArgumentException("duplicate tool-use id " + use.id());
                }
            }
            ModelMessage resultMessage = messages.get(index + 1);
            List<ModelContent.ToolResult> results = toolResults(resultMessage);
            if (resultMessage.role() != ModelRole.USER
                    || results.size() != resultMessage.content().size()
                    || results.size() != uses.size()) {
                throw new IllegalArgumentException("tool result message does not match tool uses");
            }
            for (int resultIndex = 0; resultIndex < uses.size(); resultIndex++) {
                if (!uses.get(resultIndex).id().equals(results.get(resultIndex).toolUseId())) {
                    throw new IllegalArgumentException("tool results are missing or out of order");
                }
            }
            units.add(new Unit(index, index + 2, true, List.of(message, resultMessage)));
            index += 2;
        }
        return List.copyOf(units);
    }

    public static void requireBoundary(List<Unit> units, int boundary, int messageCount) {
        if (boundary < 0 || boundary > messageCount) {
            throw new IllegalArgumentException("protected context boundary is out of range");
        }
        if (boundary == 0 || boundary == messageCount) {
            return;
        }
        boolean found = units.stream().anyMatch(unit -> unit.fromIndex() == boundary);
        if (!found) {
            throw new IllegalArgumentException("protected context boundary splits a structural unit");
        }
    }

    /** Removes provider-private reasoning before content enters a summary prompt. */
    public static List<ModelMessage> summarySafe(List<ModelMessage> messages) {
        ArrayList<ModelMessage> safe = new ArrayList<>();
        for (ModelMessage message : messages) {
            List<ModelContent> content = message.content().stream()
                    .filter(item -> !(item instanceof ModelContent.Reasoning))
                    .toList();
            if (!content.isEmpty()) {
                safe.add(new ModelMessage(message.role(), content));
            }
        }
        return List.copyOf(safe);
    }

    private static List<ModelContent.ToolUse> toolUses(ModelMessage message) {
        return message.content().stream()
                .filter(ModelContent.ToolUse.class::isInstance)
                .map(ModelContent.ToolUse.class::cast)
                .toList();
    }

    private static List<ModelContent.ToolResult> toolResults(ModelMessage message) {
        return message.content().stream()
                .filter(ModelContent.ToolResult.class::isInstance)
                .map(ModelContent.ToolResult.class::cast)
                .toList();
    }
}
