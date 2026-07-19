package dev.tomewisp.guide.history;

import dev.tomewisp.guide.GuideFailure;
import java.util.List;
import java.util.Optional;

public record GuideHistoryLoad(
        Optional<GuideHistoryPartition> partition,
        List<GuideFailure> diagnostics) {
    public GuideHistoryLoad {
        partition = partition == null ? Optional.empty() : partition;
        diagnostics = List.copyOf(diagnostics);
    }

    public static GuideHistoryLoad empty() {
        return new GuideHistoryLoad(Optional.empty(), List.of());
    }
}
