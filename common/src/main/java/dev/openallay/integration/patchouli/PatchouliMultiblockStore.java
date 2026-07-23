package dev.openallay.integration.patchouli;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PatchouliMultiblockStore {
    private volatile Map<String, PatchouliMultiblock> snapshot = Map.of();

    public void replace(Map<String, PatchouliMultiblock> multiblocks) {
        snapshot = Map.copyOf(multiblocks);
    }

    public Optional<PatchouliMultiblock> find(String id) {
        return Optional.ofNullable(snapshot.get(id));
    }

    public List<String> ids() {
        return snapshot.keySet().stream().sorted().toList();
    }

    /** Returns one immutable generation for request-scoped VFS capture. */
    public Map<String, PatchouliMultiblock> snapshot() {
        return snapshot;
    }
}
