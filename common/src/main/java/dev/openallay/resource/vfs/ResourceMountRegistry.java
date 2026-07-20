package dev.openallay.resource.vfs;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Set;
import java.util.function.Supplier;

public final class ResourceMountRegistry {
    private final Map<String, Slot> slots = new TreeMap<>();

    public synchronized void register(ResourceMount mount) {
        Objects.requireNonNull(mount, "mount");
        String name = validateRoot(mount.root());
        if (slots.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate resource mount: /" + name);
        }
        slots.put(name, new Slot(mount));
    }

    public synchronized ResourceGeneration publish(String mountName) {
        Slot slot = requireSlot(mountName);
        ResourceSnapshot snapshot = Objects.requireNonNull(slot.mount.snapshot(), "mount snapshot");
        if (!snapshot.root().equals(slot.mount.root())) {
            throw new IllegalArgumentException("Snapshot root differs from registered mount");
        }
        ResourceGeneration generation = new ResourceGeneration(snapshot);
        if (slot.generations.containsKey(generation.id())) {
            throw new IllegalArgumentException("Duplicate generation ID for /" + mountName);
        }
        validateLinks(generation, mountName);
        slot.generations.put(generation.id(), new RetainedGeneration(generation));
        slot.current = generation.id();
        prune(slot);
        return generation;
    }

    public synchronized ResourceView openView(ResourceViewScope scope) {
        return openView(scope, Set.of());
    }

    /**
     * Captures fixed generations once while selected dynamic mounts resolve their latest atomically
     * published generation on each operation. Only request-owned mounts such as {@code /result}
     * should be dynamic.
     */
    public synchronized ResourceView openView(ResourceViewScope scope, Set<String> dynamicMounts) {
        Objects.requireNonNull(scope, "scope");
        Set<String> dynamic = Set.copyOf(Objects.requireNonNull(dynamicMounts, "dynamicMounts"));
        for (String mount : dynamic) {
            requireSlot(mount);
        }
        LinkedHashMap<String, ResourceGeneration> selected = new LinkedHashMap<>();
        LinkedHashMap<String, String> retained = new LinkedHashMap<>();
        LinkedHashMap<String, Supplier<ResourceGeneration>> dynamicSelected = new LinkedHashMap<>();
        for (Map.Entry<String, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            if (slot.current == null) {
                continue;
            }
            if (dynamic.contains(entry.getKey())) {
                String mount = entry.getKey();
                dynamicSelected.put(mount, () -> currentGeneration(mount));
                continue;
            }
            RetainedGeneration generation = slot.generations.get(slot.current);
            generation.references++;
            selected.put(entry.getKey(), generation.value);
            retained.put(entry.getKey(), generation.value.id());
        }
        return new ResourceView(scope, selected, dynamicSelected, () -> release(retained));
    }

    public synchronized Map<String, String> currentGenerationIds() {
        HashMap<String, String> result = new HashMap<>();
        slots.forEach((name, slot) -> {
            if (slot.current != null) {
                result.put(name, slot.current);
            }
        });
        return Map.copyOf(result);
    }

    private synchronized void release(Map<String, String> retained) {
        retained.forEach((name, id) -> {
            Slot slot = slots.get(name);
            if (slot == null) {
                return;
            }
            RetainedGeneration generation = slot.generations.get(id);
            if (generation != null && generation.references > 0) {
                generation.references--;
            }
            prune(slot);
        });
    }

    private synchronized ResourceGeneration currentGeneration(String mountName) {
        Slot slot = slots.get(mountName);
        if (slot == null || slot.current == null) {
            return null;
        }
        RetainedGeneration generation = slot.generations.get(slot.current);
        return generation == null ? null : generation.value;
    }

    private void validateLinks(ResourceGeneration candidate, String mountName) {
        for (ResourceNode node : candidate.nodes().values()) {
            for (ResourceLink link : node.links()) {
                String targetMount = link.target().mount();
                if (targetMount.equals(mountName)) {
                    if (!candidate.nodes().containsKey(link.target())) {
                        throw new IllegalArgumentException("Resource link target is missing: " + link.target());
                    }
                    continue;
                }
                Slot targetSlot = slots.get(targetMount);
                if (targetSlot == null || targetSlot.current == null
                        || !targetSlot.generations.get(targetSlot.current).value.nodes().containsKey(link.target())) {
                    throw new IllegalArgumentException("Cross-mount resource link target is missing: " + link.target());
                }
            }
        }
    }

    private static void prune(Slot slot) {
        slot.generations.entrySet().removeIf(entry -> !entry.getKey().equals(slot.current) && entry.getValue().references == 0);
    }

    private Slot requireSlot(String mountName) {
        Slot slot = slots.get(mountName);
        if (slot == null) {
            throw new IllegalArgumentException("Unknown resource mount: /" + mountName);
        }
        return slot;
    }

    private static String validateRoot(ResourcePath root) {
        Objects.requireNonNull(root, "root");
        if (root.segments().size() != 1) {
            throw new IllegalArgumentException("Mount root must contain one segment");
        }
        return root.mount();
    }

    private static final class Slot {
        private final ResourceMount mount;
        private final Map<String, RetainedGeneration> generations = new LinkedHashMap<>();
        private String current;

        private Slot(ResourceMount mount) {
            this.mount = mount;
        }
    }

    private static final class RetainedGeneration {
        private final ResourceGeneration value;
        private int references;

        private RetainedGeneration(ResourceGeneration value) {
            this.value = value;
        }
    }
}
