package dev.openallay.resource.vfs;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class ResourceView implements AutoCloseable {
    private final ResourceViewScope scope;
    private final Map<String, ResourceGeneration> generations;
    private final Map<String, Supplier<ResourceGeneration>> dynamicGenerations;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    ResourceView(ResourceViewScope scope, Map<String, ResourceGeneration> generations, Runnable release) {
        this(scope, generations, Map.of(), release);
    }

    ResourceView(
            ResourceViewScope scope,
            Map<String, ResourceGeneration> generations,
            Map<String, Supplier<ResourceGeneration>> dynamicGenerations,
            Runnable release) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.generations = Map.copyOf(Objects.requireNonNull(generations, "generations"));
        this.dynamicGenerations = Map.copyOf(Objects.requireNonNull(dynamicGenerations, "dynamicGenerations"));
        if (this.generations.keySet().stream().anyMatch(this.dynamicGenerations::containsKey)) {
            throw new IllegalArgumentException("A Resource mount cannot be both fixed and dynamic");
        }
        this.release = Objects.requireNonNull(release, "release");
    }

    public ResourceViewScope scope() {
        return scope;
    }

    public Map<String, String> generationIds() {
        java.util.TreeMap<String, String> result = new java.util.TreeMap<>();
        generations.forEach((name, generation) -> result.put(name, generation.id()));
        dynamicGenerations.forEach((name, generation) -> result.put(name, requireDynamic(name, generation).id()));
        return Map.copyOf(result);
    }

    public ResourceNode require(ResourcePath path) {
        ensureUsable();
        ResourceGeneration generation = selected(path.mount());
        if (generation == null) {
            throw new ResourceOperationException("resource_not_found", "Unknown resource mount: /" + path.mount());
        }
        return generation.require(path);
    }

    public ResourceGeneration generation(String mount) {
        ensureUsable();
        ResourceGeneration generation = selected(mount);
        if (generation == null) {
            throw new ResourceOperationException("resource_not_found", "Unknown resource mount: /" + mount);
        }
        return generation;
    }

    private ResourceGeneration selected(String mount) {
        ResourceGeneration fixed = generations.get(mount);
        if (fixed != null) {
            return fixed;
        }
        Supplier<ResourceGeneration> dynamic = dynamicGenerations.get(mount);
        return dynamic == null ? null : requireDynamic(mount, dynamic);
    }

    private static ResourceGeneration requireDynamic(
            String mount, Supplier<ResourceGeneration> supplier) {
        ResourceGeneration generation = supplier.get();
        if (generation == null) {
            throw new ResourceOperationException(
                    "stale_resource", "Dynamic resource mount is unavailable: /" + mount);
        }
        return generation;
    }

    public boolean isClosed() {
        return closed.get();
    }

    private void ensureUsable() {
        if (closed.get()) {
            throw new ResourceOperationException("stale_resource", "Resource view has been released");
        }
        if (scope.cancelled().getAsBoolean()) {
            throw new ResourceOperationException("agent_cancelled", "Resource view request was cancelled");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release.run();
        }
    }
}
