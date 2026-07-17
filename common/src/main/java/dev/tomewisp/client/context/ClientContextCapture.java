package dev.tomewisp.client.context;

import com.google.gson.Gson;
import dev.tomewisp.context.BlockPositionSnapshot;
import dev.tomewisp.context.CallerKind;
import dev.tomewisp.context.CallerSnapshot;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ContextMetrics;
import dev.tomewisp.context.DataAuthority;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.IngredientAlternativeSnapshot;
import dev.tomewisp.context.IngredientRequirementSnapshot;
import dev.tomewisp.context.InventorySnapshot;
import dev.tomewisp.context.InventorySlotSnapshot;
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
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.platform.PlatformServices;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

public final class ClientContextCapture {
    private final Gson gson;
    private final PlatformService platform;

    public ClientContextCapture(Gson gson) {
        this(gson, PlatformServices.load());
    }

    public ClientContextCapture(Gson gson, PlatformService platform) {
        this.gson = gson;
        this.platform = platform;
    }

    public ToolInvocationContext capture(
            Minecraft client, Set<ContextCapability> capabilities, String correlationId) {
        if (!client.isSameThread()) {
            throw new IllegalStateException("Client context must be captured on the Minecraft client thread");
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            throw new IllegalStateException("No active client player or level");
        }
        long started = System.nanoTime();
        Instant capturedAt = Instant.now();
        CallerSnapshot caller = new CallerSnapshot(
                CallerKind.PLAYER, player.getUUID(), player.getName().getString(), false);
        Optional<PlayerSnapshot> playerSnapshot = capabilities.contains(ContextCapability.PLAYER)
                ? Optional.of(player(player, client, capturedAt))
                : Optional.empty();
        Optional<RegistrySnapshot> registries = capabilities.contains(ContextCapability.REGISTRIES)
                ? Optional.of(registries(capturedAt))
                : Optional.empty();
        Optional<RecipeSnapshot> recipes = capabilities.contains(ContextCapability.RECIPES)
                ? Optional.of(recipes(player, client, capturedAt))
                : Optional.empty();
        long bytes = bytes(caller)
                + bytes(playerSnapshot.orElse(null))
                + bytes(registries.orElse(null))
                + bytes(recipes.orElse(null));
        return new ToolInvocationContext(
                correlationId,
                capturedAt,
                caller,
                playerSnapshot,
                registries,
                recipes,
                new ContextMetrics(
                        registries.map(value -> (long) value.entries().size()).orElse(0L),
                        recipes.map(value -> (long) value.recipes().size()).orElse(0L),
                        playerSnapshot.map(value -> (long) value.inventory().slots().size()).orElse(0L),
                        bytes,
                        System.nanoTime() - started));
    }

    private PlayerSnapshot player(LocalPlayer player, Minecraft client, Instant capturedAt) {
        List<InventorySlotSnapshot> inventory = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            inventory.add(new InventorySlotSnapshot(slot, stack(player.getInventory().getItem(slot))));
        }
        var position = player.blockPosition();
        String mode = client.gameMode == null || client.gameMode.getPlayerMode() == null
                ? "unknown"
                : client.gameMode.getPlayerMode().getName();
        EvidenceMetadata evidence = evidence(
                DataCompleteness.COMPLETE,
                capturedAt,
                "minecraft:client_player",
                "minecraft:client_player");
        int selected = player.getInventory().getSelectedSlot();
        InventorySnapshot inventorySnapshot = new InventorySnapshot(
                inventory,
                player.getInventory().getContainerSize(),
                selected,
                selected,
                stack(player.getOffhandItem()),
                true,
                evidence);
        return new PlayerSnapshot(
                player.getUUID(),
                player.getName().getString(),
                player.level().dimension().identifier().toString(),
                new BlockPositionSnapshot(position.getX(), position.getY(), position.getZ()),
                mode,
                inventorySnapshot,
                evidence);
    }

    private RegistrySnapshot registries(Instant capturedAt) {
        List<RegistryEntrySnapshot> entries = new ArrayList<>();
        BuiltInRegistries.ITEM.stream().forEach(item -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            entries.add(new RegistryEntrySnapshot(
                    id.toString(), "item", new ItemStack(item).getHoverName().getString(),
                    id.getNamespace(), "minecraft:client_registry"));
        });
        BuiltInRegistries.BLOCK.stream().forEach(block -> {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            entries.add(new RegistryEntrySnapshot(
                    id.toString(), "block", block.getName().getString(),
                    id.getNamespace(), "minecraft:client_registry"));
        });
        entries.sort(Comparator.comparing(RegistryEntrySnapshot::id)
                .thenComparing(RegistryEntrySnapshot::kind));
        return new RegistrySnapshot(evidence(
                DataCompleteness.COMPLETE,
                capturedAt,
                "minecraft:client_registry",
                "minecraft:client_registry"), entries);
    }

    private RecipeSnapshot recipes(LocalPlayer player, Minecraft client, Instant capturedAt) {
        Set<Integer> seen = new HashSet<>();
        List<RecipeEntrySnapshot> recipes = new ArrayList<>();
        var context = SlotDisplayContext.fromLevel(client.level);
        for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (!seen.add(entry.id().index())) {
                    continue;
                }
                List<IngredientRequirementSnapshot> ingredients = new ArrayList<>();
                List<net.minecraft.world.item.crafting.Ingredient> requirements =
                        entry.craftingRequirements().orElse(List.of());
                for (int index = 0; index < requirements.size(); index++) {
                    net.minecraft.world.item.crafting.Ingredient ingredient = requirements.get(index);
                    ingredients.add(new IngredientRequirementSnapshot(
                            "input-" + index,
                            1,
                            true,
                            ingredient.items().map(holder -> {
                                String id = holder.unwrapKey().orElseThrow().identifier().toString();
                                return new IngredientAlternativeSnapshot("item", id, List.of(id));
                            }).toList()));
                }
                List<RecipeOutputSnapshot> outputs = entry.resultItems(context).stream()
                        .map(value -> new RecipeOutputSnapshot(stack(value), 1.0D))
                        .toList();
                String recipeId = "tomewisp:client_recipe_display/" + entry.id().index();
                RecipeLayoutSnapshot layout = entry.display() instanceof ShapedCraftingRecipeDisplay shaped
                        ? new RecipeLayoutSnapshot(shaped.width(), shaped.height(), true)
                        : entry.display() instanceof ShapelessCraftingRecipeDisplay
                                ? new RecipeLayoutSnapshot(0, 0, false)
                                : RecipeLayoutSnapshot.unknown();
                String workstation = entry.display().craftingStation().resolveForStacks(context).stream()
                        .filter(value -> !value.isEmpty())
                        .map(value -> BuiltInRegistries.ITEM.getKey(value.getItem()).toString())
                        .findFirst()
                        .orElse(null);
                EvidenceMetadata recipeEvidence = evidence(
                        DataCompleteness.COMPLETE,
                        capturedAt,
                        "minecraft:client_recipe_book",
                        "minecraft:client_recipe_display");
                recipes.add(new RecipeEntrySnapshot(
                        new RecipeReference("minecraft:client_recipe_book", recipeId),
                        recipeId,
                        java.util.Objects.requireNonNull(
                                        BuiltInRegistries.RECIPE_DISPLAY.getKey(entry.display().type()))
                                .toString(),
                        layout,
                        workstation,
                        ingredients,
                        List.of(),
                        List.of(),
                        outputs,
                        List.of(),
                        RecipeProcessingSnapshot.unknown(),
                        List.of(),
                        Map.of(),
                        recipeEvidence));
            }
        }
        recipes.sort(Comparator.comparing(RecipeEntrySnapshot::id));
        return new RecipeSnapshot(evidence(
                DataCompleteness.COMPLETE,
                capturedAt,
                "minecraft:client_recipe_book",
                "minecraft:client_recipe_display"), recipes);
    }

    private ItemStackSnapshot stack(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStackSnapshot.empty();
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new ItemStackSnapshot(id.toString(), stack.getCount(), stack.getHoverName().getString());
    }

    private long bytes(Object value) {
        return value == null ? 0 : gson.toJson(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private EvidenceMetadata evidence(
            DataCompleteness completeness,
            Instant capturedAt,
            String source,
            String provenance) {
        return new EvidenceMetadata(
                DataAuthority.CLIENT_VISIBLE,
                completeness,
                capturedAt,
                source,
                provenance,
                platform.gameVersion(),
                platform.platformName().toLowerCase(Locale.ROOT),
                Map.of());
    }
}
