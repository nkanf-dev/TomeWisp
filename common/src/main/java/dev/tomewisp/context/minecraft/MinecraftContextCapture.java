package dev.tomewisp.context.minecraft;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;

public final class MinecraftContextCapture {
    private static final String REGISTRY_PROVENANCE = "minecraft:registry";
    private static final String RECIPE_PROVENANCE = "minecraft:recipe_manager";
    private final Gson gson;
    private final PlatformService platform;

    public MinecraftContextCapture(Gson gson) {
        this(gson, PlatformServices.load());
    }

    public MinecraftContextCapture(Gson gson, PlatformService platform) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public ToolInvocationContext capture(
            CommandSourceStack source,
            Set<ContextCapability> capabilities,
            String correlationId) {
        Objects.requireNonNull(source, "source");
        capabilities = Set.copyOf(capabilities);
        if (!source.getServer().isSameThread()) {
            throw new IllegalStateException("Minecraft context must be captured on server thread");
        }

        long started = System.nanoTime();
        java.time.Instant capturedAt = java.time.Instant.now();
        ServerPlayer serverPlayer = source.getPlayer();
        CallerSnapshot caller = serverPlayer == null
                ? new CallerSnapshot(CallerKind.CONSOLE, null, source.getTextName(), true)
                : new CallerSnapshot(
                        CallerKind.PLAYER,
                        serverPlayer.getUUID(),
                        source.getTextName(),
                        source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));

        Optional<PlayerSnapshot> player = capabilities.contains(ContextCapability.PLAYER)
                && serverPlayer != null
                ? Optional.of(capturePlayer(serverPlayer, capturedAt))
                : Optional.empty();
        Optional<RegistrySnapshot> registries = capabilities.contains(ContextCapability.REGISTRIES)
                ? Optional.of(captureRegistries(capturedAt))
                : Optional.empty();
        Optional<RecipeSnapshot> recipes = capabilities.contains(ContextCapability.RECIPES)
                ? Optional.of(captureRecipes(source, capturedAt))
                : Optional.empty();

        long bytes = serializedBytes(caller)
                + serializedBytes(player.orElse(null))
                + serializedBytes(registries.orElse(null))
                + serializedBytes(recipes.orElse(null));
        ContextMetrics metrics = new ContextMetrics(
                registries.map(value -> (long) value.entries().size()).orElse(0L),
                recipes.map(value -> (long) value.recipes().size()).orElse(0L),
                player.map(value -> (long) value.inventory().slots().size()).orElse(0L),
                bytes,
                System.nanoTime() - started);
        return new ToolInvocationContext(
                correlationId, capturedAt, caller, player, registries, recipes, metrics);
    }

    private PlayerSnapshot capturePlayer(ServerPlayer player, java.time.Instant capturedAt) {
        List<InventorySlotSnapshot> inventory = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            inventory.add(new InventorySlotSnapshot(
                    slot, captureStack(player.getInventory().getItem(slot))));
        }
        BlockPos position = player.blockPosition();
        EvidenceMetadata evidence = evidence(
                DataCompleteness.COMPLETE,
                capturedAt,
                "minecraft:server_player",
                "minecraft:server_player");
        int selected = player.getInventory().getSelectedSlot();
        InventorySnapshot inventorySnapshot = new InventorySnapshot(
                inventory,
                player.getInventory().getContainerSize(),
                selected,
                selected,
                captureStack(player.getOffhandItem()),
                true,
                evidence);
        return new PlayerSnapshot(
                player.getUUID(),
                player.getName().getString(),
                player.level().dimension().identifier().toString(),
                new BlockPositionSnapshot(position.getX(), position.getY(), position.getZ()),
                player.gameMode().getName(),
                inventorySnapshot,
                evidence);
    }

    private RegistrySnapshot captureRegistries(java.time.Instant capturedAt) {
        List<RegistryEntrySnapshot> entries = new ArrayList<>();
        BuiltInRegistries.ITEM.stream().forEach(item -> {
            Identifier id = Objects.requireNonNull(BuiltInRegistries.ITEM.getKey(item));
            entries.add(new RegistryEntrySnapshot(
                    id.toString(),
                    "item",
                    new ItemStack(item).getHoverName().getString(),
                    id.getNamespace(),
                    REGISTRY_PROVENANCE));
        });
        BuiltInRegistries.BLOCK.stream().forEach(block -> {
            Identifier id = Objects.requireNonNull(BuiltInRegistries.BLOCK.getKey(block));
            entries.add(new RegistryEntrySnapshot(
                    id.toString(),
                    "block",
                    block.getName().getString(),
                    id.getNamespace(),
                    REGISTRY_PROVENANCE));
        });
        entries.sort(Comparator.comparing(RegistryEntrySnapshot::id)
                .thenComparing(RegistryEntrySnapshot::kind));
        return new RegistrySnapshot(evidence(
                DataCompleteness.COMPLETE,
                capturedAt,
                "minecraft:registry",
                REGISTRY_PROVENANCE), entries);
    }

    private RecipeSnapshot captureRecipes(
            CommandSourceStack source, java.time.Instant capturedAt) {
        ContextMap displayContext = SlotDisplayContext.fromLevel(source.getLevel());
        List<RecipeEntrySnapshot> recipes = source.getServer().getRecipeManager().getRecipes()
                .stream()
                .map(holder -> captureRecipe(holder, displayContext, capturedAt))
                .sorted(Comparator.comparing(RecipeEntrySnapshot::id))
                .toList();
        return new RecipeSnapshot(evidence(
                DataCompleteness.COMPLETE,
                capturedAt,
                "minecraft:recipe_manager",
                RECIPE_PROVENANCE), recipes);
    }

    private RecipeEntrySnapshot captureRecipe(
            RecipeHolder<?> holder,
            ContextMap displayContext,
            java.time.Instant capturedAt) {
        List<IngredientRequirementSnapshot> ingredients = new ArrayList<>();
        List<Ingredient> rawIngredients = holder.value().placementInfo().ingredients();
        for (int index = 0; index < rawIngredients.size(); index++) {
            ingredients.add(captureIngredient(index, rawIngredients.get(index)));
        }
        List<RecipeDisplay> displays = holder.value().display();
        List<RecipeOutputSnapshot> outputs = displays.stream()
                .flatMap(display -> display.result().resolveForStacks(displayContext).stream())
                .map(value -> new RecipeOutputSnapshot(captureStack(value), 1.0D))
                .toList();
        Identifier type = Objects.requireNonNull(
                BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()));
        RecipeDisplay primary = displays.isEmpty() ? null : displays.getFirst();
        RecipeLayoutSnapshot layout = primary instanceof ShapedCraftingRecipeDisplay shaped
                ? new RecipeLayoutSnapshot(shaped.width(), shaped.height(), true)
                : primary instanceof ShapelessCraftingRecipeDisplay
                        ? new RecipeLayoutSnapshot(0, 0, false)
                        : RecipeLayoutSnapshot.unknown();
        String workstation = primary == null
                ? null
                : primary.craftingStation().resolveForStacks(displayContext).stream()
                        .filter(value -> !value.isEmpty())
                        .map(value -> BuiltInRegistries.ITEM.getKey(value.getItem()).toString())
                        .findFirst()
                        .orElse(null);
        String recipeId = holder.id().identifier().toString();
        EvidenceMetadata recipeEvidence = evidence(
                DataCompleteness.COMPLETE,
                capturedAt,
                "minecraft:recipe_manager",
                RECIPE_PROVENANCE);
        return new RecipeEntrySnapshot(
                new RecipeReference("minecraft:recipe_manager", recipeId),
                recipeId,
                type.toString(),
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
                recipeEvidence);
    }

    private IngredientRequirementSnapshot captureIngredient(int index, Ingredient ingredient) {
        List<IngredientAlternativeSnapshot> alternatives = ingredient.items()
                .map(holder -> {
                    String id = holder.unwrapKey()
                            .orElseThrow(() -> new IllegalStateException("Unbound recipe item"))
                            .identifier()
                            .toString();
                    return new IngredientAlternativeSnapshot("item", id, List.of(id));
                })
                .toList();
        return new IngredientRequirementSnapshot("input-" + index, 1, true, alternatives);
    }

    private ItemStackSnapshot captureStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStackSnapshot.empty();
        }
        Identifier id = Objects.requireNonNull(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return new ItemStackSnapshot(id.toString(), stack.getCount(), stack.getHoverName().getString());
    }

    private long serializedBytes(Object value) {
        if (value == null) {
            return 0;
        }
        return gson.toJson(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private EvidenceMetadata evidence(
            DataCompleteness completeness,
            java.time.Instant capturedAt,
            String source,
            String provenance) {
        return new EvidenceMetadata(
                DataAuthority.SERVER_AUTHORITATIVE,
                completeness,
                capturedAt,
                source,
                provenance,
                platform.gameVersion(),
                platform.platformName().toLowerCase(Locale.ROOT),
                Map.of());
    }
}
