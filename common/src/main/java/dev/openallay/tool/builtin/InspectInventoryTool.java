package dev.openallay.tool.builtin;

import dev.openallay.context.ContextCapability;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.InventorySnapshot;
import dev.openallay.context.ItemStackSnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class InspectInventoryTool
        implements Tool<InspectInventoryTool.Input, InspectInventoryTool.Output> {
    public record Input() {}

    public record Output(
            InventorySnapshot inventory,
            Map<String, Long> counts,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            counts = Collections.unmodifiableSortedMap(new TreeMap<>(counts));
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:inspect_inventory",
            "Return the captured player inventory and item counts with explicit completeness evidence",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY,
            Set.of(ContextCapability.PLAYER));

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        return context.player()
                .<ToolResult<Output>>map(player -> {
                    InventorySnapshot inventory = player.inventory();
                    TreeMap<String, Long> counts = new TreeMap<>();
                    inventory.slots().forEach(slot -> add(counts, slot.stack()));
                    add(counts, inventory.offHand());
                    return new ToolResult.Success<>(new Output(
                            inventory,
                            counts,
                            List.of(inventory.evidence(), player.evidence())));
                })
                .orElseGet(() -> new ToolResult.Failure<>(
                        "player_required", "this tool requires a player invocation context"));
    }

    private static void add(Map<String, Long> counts, ItemStackSnapshot stack) {
        if (stack.count() == 0 || stack.itemId().equals("minecraft:air")) {
            return;
        }
        counts.merge(stack.itemId(), (long) stack.count(), Math::addExact);
    }
}
