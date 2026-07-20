package dev.openallay.resource.mount;

import dev.openallay.context.InventorySlotSnapshot;
import dev.openallay.context.ItemStackSnapshot;
import dev.openallay.context.PlayerSnapshot;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceLink;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class PlayerResourceMount implements ResourceMount {
    private final Supplier<PlayerSnapshot> source;
    private long generation;

    public PlayerResourceMount(Supplier<PlayerSnapshot> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public ResourcePath root() {
        return ResourcePath.of("player");
    }

    @Override
    public synchronized ResourceSnapshot snapshot() {
        PlayerSnapshot player = Objects.requireNonNull(source.get(), "player snapshot");
        ResourceTreeBuilder tree = new ResourceTreeBuilder(root(), player.evidence());
        tree.put(root().child("profile"), ResourceKind.RECORD, ResourceValues.record(Map.of(
                "uuid", player.uuid().toString(),
                "name", player.displayName(),
                "dimension", player.dimension(),
                "game_mode", player.gameMode(),
                "x", player.position().x(),
                "y", player.position().y(),
                "z", player.position().z())), List.of(), ResourcePresentation.none());
        for (InventorySlotSnapshot slot : player.inventory().slots()) {
            ItemStackSnapshot stack = slot.stack();
            ResourcePath slotPath = ResourcePath.of("player", "inventory", "slot", Integer.toString(slot.slot()));
            ArrayList<ResourceLink> links = new ArrayList<>();
            if (stack.count() > 0) {
                links.add(new ResourceLink("item", itemPath(stack.itemId()), stack.displayName()));
            }
            tree.put(slotPath, ResourceKind.RECORD, ResourceValues.record(Map.of(
                    "slot", slot.slot(), "item", stack.itemId(), "name", stack.displayName(), "count", stack.count())),
                    links, player.inventory().evidence(), ResourcePresentation.none());
        }
        return new ResourceSnapshot(root(), "player-" + ++generation,
                player.evidence().capturedAt(), tree.build());
    }

    private static ResourcePath itemPath(String itemId) {
        int separator = itemId.indexOf(':');
        return ResourcePath.of("item", itemId.substring(0, separator), itemId.substring(separator + 1));
    }
}
