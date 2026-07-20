package dev.openallay.resource.vfs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Complete direct-child listing for one node in one captured generation. */
public record ResourceDirectoryPage(
        ResourcePath path,
        String generationId,
        List<ResourceEntry> entries) {
    public ResourceDirectoryPage {
        Objects.requireNonNull(path, "path");
        if (generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("generationId is required");
        }
        ArrayList<ResourceEntry> copy = new ArrayList<>(Objects.requireNonNull(entries, "entries"));
        copy.sort(Comparator.naturalOrder());
        entries = List.copyOf(copy);
    }
}
