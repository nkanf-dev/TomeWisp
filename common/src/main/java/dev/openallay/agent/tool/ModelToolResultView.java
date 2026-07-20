package dev.openallay.agent.tool;

import dev.openallay.resource.projection.ResourceReceipt;
import java.util.List;

public record ModelToolResultView(
        String text,
        List<ResourceReceipt> receipts,
        List<String> semanticUnits,
        int estimatedCharacters) {
    public ModelToolResultView {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Model Tool result text is required");
        }
        receipts = List.copyOf(receipts);
        semanticUnits = List.copyOf(semanticUnits);
        if (semanticUnits.stream().anyMatch(unit -> unit == null || unit.isBlank())) {
            throw new IllegalArgumentException("Semantic Tool-result units must not be blank");
        }
        if (estimatedCharacters < 0) {
            throw new IllegalArgumentException("estimatedCharacters must be non-negative");
        }
    }

    public ModelToolResultView(
            String text, List<ResourceReceipt> receipts, int estimatedCharacters) {
        this(text, receipts, List.of(text), estimatedCharacters);
    }

    public ModelToolResultView(String text) {
        this(text, List.of(), List.of(text), text.length());
    }
}
