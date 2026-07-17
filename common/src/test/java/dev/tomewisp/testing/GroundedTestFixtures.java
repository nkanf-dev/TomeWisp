package dev.tomewisp.testing;

import com.google.gson.JsonObject;
import dev.tomewisp.context.BlockPositionSnapshot;
import dev.tomewisp.context.CallerKind;
import dev.tomewisp.context.CallerSnapshot;
import dev.tomewisp.context.ContextMetrics;
import dev.tomewisp.context.DataAuthority;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.IngredientAlternativeSnapshot;
import dev.tomewisp.context.IngredientRequirementSnapshot;
import dev.tomewisp.context.InventorySlotSnapshot;
import dev.tomewisp.context.InventorySnapshot;
import dev.tomewisp.context.ItemStackSnapshot;
import dev.tomewisp.context.PlayerSnapshot;
import dev.tomewisp.context.RecipeEntrySnapshot;
import dev.tomewisp.context.RecipeLayoutSnapshot;
import dev.tomewisp.context.RecipeOutputSnapshot;
import dev.tomewisp.context.RecipeProcessingSnapshot;
import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.context.RecipeSnapshot;
import dev.tomewisp.context.RegistryEntrySnapshot;
import dev.tomewisp.context.RegistrySnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class GroundedTestFixtures {
    public static final UUID PLAYER_ID =
            UUID.fromString("54a6fbd8-c548-4c4d-b9a7-7b9e310d2b71");

    private GroundedTestFixtures() {}

    public static EvidenceMetadata serverEvidence() {
        return new EvidenceMetadata(
                DataAuthority.SERVER_AUTHORITATIVE,
                DataCompleteness.COMPLETE,
                Instant.EPOCH,
                "minecraft:recipe_manager",
                "minecraft:recipe_manager",
                "test",
                "common-test",
                Map.of());
    }

    public static EvidenceMetadata playerEvidence() {
        return new EvidenceMetadata(
                DataAuthority.SERVER_AUTHORITATIVE,
                DataCompleteness.COMPLETE,
                Instant.EPOCH,
                "minecraft:server_player",
                "minecraft:server_player",
                "test",
                "common-test",
                Map.of());
    }

    public static RecipeEntrySnapshot ironBlockRecipe() {
        return new RecipeEntrySnapshot(
                new RecipeReference("minecraft:recipe_manager", "minecraft:iron_block"),
                "minecraft:iron_block",
                "minecraft:crafting",
                new RecipeLayoutSnapshot(3, 3, true),
                "minecraft:crafting_table",
                List.of(new IngredientRequirementSnapshot(
                        "iron",
                        9,
                        true,
                        List.of(new IngredientAlternativeSnapshot(
                                "item",
                                "minecraft:iron_ingot",
                                List.of("minecraft:iron_ingot"))))),
                List.of(),
                List.of(),
                List.of(new RecipeOutputSnapshot(
                        new ItemStackSnapshot("minecraft:iron_block", 1, "Block of Iron"),
                        1.0D)),
                List.of(),
                RecipeProcessingSnapshot.unknown(),
                List.of(),
                Map.<String, JsonObject>of(),
                serverEvidence());
    }

    public static RecipeSnapshot recipeSnapshot() {
        return new RecipeSnapshot(serverEvidence(), List.of(ironBlockRecipe()));
    }

    public static InventorySnapshot inventory() {
        return new InventorySnapshot(
                List.of(
                        new InventorySlotSnapshot(
                                0,
                                new ItemStackSnapshot(
                                        "minecraft:iron_ingot", 4, "Iron Ingot")),
                        new InventorySlotSnapshot(1, ItemStackSnapshot.empty())),
                2,
                0,
                0,
                ItemStackSnapshot.empty(),
                true,
                playerEvidence());
    }

    public static PlayerSnapshot player() {
        return new PlayerSnapshot(
                PLAYER_ID,
                "Builder",
                "minecraft:overworld",
                new BlockPositionSnapshot(1, 64, 2),
                "survival",
                inventory(),
                playerEvidence());
    }

    public static RegistrySnapshot registries() {
        EvidenceMetadata evidence = new EvidenceMetadata(
                DataAuthority.SERVER_AUTHORITATIVE,
                DataCompleteness.COMPLETE,
                Instant.EPOCH,
                "minecraft:registry",
                "minecraft:registry",
                "test",
                "common-test",
                Map.of());
        return new RegistrySnapshot(evidence, List.of(
                new RegistryEntrySnapshot(
                        "minecraft:stone", "block", "Stone", "minecraft", "minecraft:registry"),
                new RegistryEntrySnapshot(
                        "minecraft:stone", "item", "Stone", "minecraft", "minecraft:registry")));
    }

    public static ToolInvocationContext fullContext() {
        return new ToolInvocationContext(
                "test",
                Instant.EPOCH,
                new CallerSnapshot(CallerKind.PLAYER, PLAYER_ID, "Builder", true),
                Optional.of(player()),
                Optional.of(registries()),
                Optional.of(recipeSnapshot()),
                new ContextMetrics(2, 1, 2, 0, 0));
    }
}
