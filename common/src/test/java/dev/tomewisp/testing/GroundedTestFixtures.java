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
import dev.tomewisp.context.game.ObservableGameStateSnapshot;
import dev.tomewisp.platform.InstalledModMetadata;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class GroundedTestFixtures {
    public static final UUID PLAYER_ID =
            UUID.fromString("54a6fbd8-c548-4c4d-b9a7-7b9e310d2b71");
    public static final String RECIPE_GENERATION = "0".repeat(64);

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
                new RecipeReference(
                        "minecraft:recipe_manager", RECIPE_GENERATION, "minecraft:iron_block"),
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

    public static InventorySnapshot inventory(Map<String, Long> counts) {
        return inventory(counts, DataCompleteness.COMPLETE);
    }

    public static InventorySnapshot inventory(
            Map<String, Long> counts, DataCompleteness completeness) {
        ArrayList<InventorySlotSnapshot> slots = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> slots.add(new InventorySlotSnapshot(
                        slots.size(),
                        new ItemStackSnapshot(
                                entry.getKey(), Math.toIntExact(entry.getValue()), entry.getKey()))));
        EvidenceMetadata base = playerEvidence();
        EvidenceMetadata evidence = new EvidenceMetadata(
                base.authority(),
                completeness,
                base.capturedAt(),
                base.sourceId(),
                base.provenance(),
                base.gameVersion(),
                base.loader(),
                base.details());
        return new InventorySnapshot(
                slots,
                slots.size(),
                slots.isEmpty() ? -1 : 0,
                slots.isEmpty() ? -1 : 0,
                ItemStackSnapshot.empty(),
                completeness == DataCompleteness.COMPLETE,
                evidence);
    }

    public static RecipeEntrySnapshot overlappingAlternativeRecipe() {
        IngredientAlternativeSnapshot oak = new IngredientAlternativeSnapshot(
                "item", "minecraft:oak_planks", List.of("minecraft:oak_planks"));
        IngredientAlternativeSnapshot planks = new IngredientAlternativeSnapshot(
                "tag",
                "minecraft:planks",
                List.of("minecraft:birch_planks", "minecraft:oak_planks"));
        return new RecipeEntrySnapshot(
                new RecipeReference(
                        "minecraft:recipe_manager", RECIPE_GENERATION, "test:overlapping_planks"),
                "test:overlapping_planks",
                "minecraft:crafting",
                new RecipeLayoutSnapshot(2, 1, true),
                "minecraft:crafting_table",
                List.of(
                        new IngredientRequirementSnapshot("flexible", 1, true, List.of(planks)),
                        new IngredientRequirementSnapshot("oak", 1, true, List.of(oak))),
                List.of(),
                List.of(),
                List.of(new RecipeOutputSnapshot(
                        new ItemStackSnapshot("minecraft:stick", 4, "Stick"), 1.0D)),
                List.of(),
                RecipeProcessingSnapshot.unknown(),
                List.of(),
                Map.of(),
                serverEvidence());
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
                        "minecraft:stone", "item", "Stone", "minecraft", "minecraft:registry"),
                new RegistryEntrySnapshot(
                        "minecraft:iron_block", "item", "Block of Iron", "minecraft",
                        "minecraft:registry")));
    }

    public static ToolInvocationContext fullContext() {
        return new ToolInvocationContext(
                "test",
                Instant.EPOCH,
                new CallerSnapshot(CallerKind.PLAYER, PLAYER_ID, "Builder", true),
                Optional.of(player()),
                Optional.of(registries()),
                Optional.of(recipeSnapshot()),
                Optional.of(observableGameState()),
                new ContextMetrics(3, 1, 2, 0, 0));
    }

    public static ObservableGameStateSnapshot observableGameState() {
        EvidenceMetadata evidence = playerEvidence();
        return new ObservableGameStateSnapshot(
                Instant.EPOCH,
                new ObservableGameStateSnapshot.RuntimeState(
                        "test", "common-test", true, "singleplayer", evidence, List.of()),
                new ObservableGameStateSnapshot.ModsState(
                        List.of(new InstalledModMetadata(
                                "tomewisp", "TomeWisp", "test", "fixture", List.of(),
                                List.of(), Map.of(), "client", List.of())),
                        evidence,
                        List.of()),
                new ObservableGameStateSnapshot.OptionsState(List.of(), evidence, List.of()),
                new ObservableGameStateSnapshot.PacksState(List.of(), List.of(), evidence, List.of()),
                new ObservableGameStateSnapshot.ShaderState(
                        false, "none", "", Map.of(), evidence, List.of()),
                new ObservableGameStateSnapshot.DiagnosticsState(
                        List.of(new ObservableGameStateSnapshot.DiagnosticValue(
                                "position", "coordinates", "1 64 2")),
                        evidence,
                        List.of()),
                new ObservableGameStateSnapshot.PlayerUiState(
                        player(), "gameplay", "", evidence, List.of()),
                new ObservableGameStateSnapshot.WorldQueriesState(
                        Map.of("time", new ObservableGameStateSnapshot.QueryValue(
                                "time", "0", true, "server_authoritative")),
                        evidence,
                        List.of()));
    }
}
