package dev.openallay.client.resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Creates detached UTF-8 snapshots; no reload-era Resource object is retained. */
public final class MinecraftClientResourceAccess implements ClientResourceAccess {
    private final ResourceManager resources;

    public MinecraftClientResourceAccess(ResourceManager resources) {
        this.resources = java.util.Objects.requireNonNull(resources, "resources");
    }

    @Override
    public List<ClientResource> list(String pathPrefix) {
        String prefix = ClientResourceAccess.validatePrefix(pathPrefix);
        Map<net.minecraft.resources.Identifier, List<Resource>> stacks =
                resources.listResourceStacks(prefix, id -> id.getPath().startsWith(prefix));
        List<ClientResource> detached = new ArrayList<>();
        stacks.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            List<Resource> stack = entry.getValue();
            for (int index = 0; index < stack.size(); index++) {
                Resource resource = stack.get(index);
                try (java.io.InputStream input = resource.open()) {
                    detached.add(new ClientResource(
                            entry.getKey().toString(),
                            resource.sourcePackId(),
                            index,
                            index == stack.size() - 1,
                            new String(input.readAllBytes(), StandardCharsets.UTF_8)));
                } catch (IOException failure) {
                    throw new UncheckedIOException(
                            "Failed reading client resource " + entry.getKey(), failure);
                }
            }
        });
        detached.sort(Comparator.comparing(ClientResource::resourceId)
                .thenComparingInt(ClientResource::priority));
        return List.copyOf(detached);
    }
}
