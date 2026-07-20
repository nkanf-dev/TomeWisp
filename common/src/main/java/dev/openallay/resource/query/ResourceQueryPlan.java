package dev.openallay.resource.query;

import dev.openallay.resource.vfs.ResourcePath;
import java.util.List;
import java.util.Objects;

public record ResourceQueryPlan(List<ResourcePath> roots, List<ResourceQueryStage> stages) {
    public ResourceQueryPlan {
        roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        if (roots.isEmpty()) throw new IllegalArgumentException("At least one query root is required");
        if (stages.isEmpty()) throw new IllegalArgumentException("At least one query stage is required");
    }
}
