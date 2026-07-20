package dev.openallay.resource.vfs;

import java.util.Objects;

public final class ResourceViewFactory {
    private final ResourceMountRegistry registry;

    public ResourceViewFactory(ResourceMountRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public ResourceView capture(ResourceViewScope scope) {
        return registry.openView(scope);
    }
}
