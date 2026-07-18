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
import dev.tomewisp.recipe.RecipeKnowledgeProvider;
import dev.tomewisp.recipe.RecipeKnowledgeService;
import dev.tomewisp.recipe.RecipeProviderSnapshot;
import dev.tomewisp.recipe.RecipeProviderReadiness;
import dev.tomewisp.recipe.RecipeProviderReadinessGate;
import dev.tomewisp.recipe.RecipeUnlockState;
import dev.tomewisp.recipe.RecipeViewerProviderRegistry;
import dev.tomewisp.recipe.config.RecipeClientConfig;
import dev.tomewisp.recipe.config.RecipeClientRuntime;
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
    private static final String CAPTURE_GENERATION_PLACEHOLDER = "0".repeat(64);
    private final Gson gson;
    private final PlatformService platform;
    private final RecipeClientRuntime recipeClient;
    private final RecipeKnowledgeService recipeKnowledge = new RecipeKnowledgeService();

    public ClientContextCapture(Gson gson) {
        this(gson, PlatformServices.load(), RecipeClientRuntime.defaults());
    }

    public ClientContextCapture(Gson gson, PlatformService platform) {
        this(gson, platform, RecipeClientRuntime.defaults());
    }

    public ClientContextCapture(
            Gson gson, PlatformService platform, RecipeClientRuntime recipeClient) {
        this.gson = gson;
        this.platform = platform;
        this.recipeClient = java.util.Objects.requireNonNull(recipeClient, "recipeClient");
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

    public RecipeProviderReadiness recipeProviderReadiness(
            Minecraft client, RecipeProviderReadinessGate gate) {
        if (!client.isSameThread()) {
            throw new IllegalStateException("Recipe readiness must be sampled on the Minecraft client thread");
        }
        List<String> required = new ArrayList<>();
        if (recipeClient.sourceEnabled("viewer:jei") && platform.isModLoaded("jei")) {
            required.add("viewer:jei");
        }
        if (recipeClient.sourceEnabled("viewer:rei")
                && platform.isModLoaded("roughlyenoughitems")) {
            required.add("viewer:rei");
        }
        return gate.evaluate(
                required,
                RecipeViewerProviderRegistry.providers(Instant.now(), platform));
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
        RecipeClientConfig config = recipeClient.config();
        RecipeKnowledgeProvider vanilla = new RecipeKnowledgeProvider() {
            @Override
            public String sourceId() {
                return "minecraft:client_recipe_book";
            }

            @Override
            public RecipeProviderSnapshot capture() {
                return vanillaRecipes(player, client, capturedAt);
            }
        };
        List<RecipeKnowledgeProvider> providers = new ArrayList<>();
        providers.add(recipeClient.sourceEnabled("minecraft:client_recipe_book")
                ? vanilla
                : inactiveViewer(
                        "minecraft:client_recipe_book",
                        "disabled_by_config",
                        "Vanilla recipe-book capture is disabled"));
        List<RecipeKnowledgeProvider> registered = RecipeViewerProviderRegistry.providers(
                capturedAt, platform);
        addViewerProvider(
                providers,
                registered,
                "viewer:jei",
                "jei",
                recipeClient.sourceEnabled("viewer:jei"),
                "JEI");
        addViewerProvider(
                providers,
                registered,
                "viewer:rei",
                "roughlyenoughitems",
                recipeClient.sourceEnabled("viewer:rei"),
                "REI");
        registered.stream()
                .filter(provider -> providers.stream().noneMatch(existing ->
                        existing.sourceId().equals(provider.sourceId())))
                .filter(provider -> recipeClient.sourceEnabled(provider.sourceId()))
                .forEach(providers::add);
        return recipeKnowledge.capture(
                evidence(
                        DataCompleteness.UNKNOWN,
                        capturedAt,
                        "tomewisp:recipe_catalog",
                        "tomewisp:recipe_catalog"),
                config.visibility(),
                providers);
    }

    private void addViewerProvider(
            List<RecipeKnowledgeProvider> providers,
            List<RecipeKnowledgeProvider> registered,
            String sourceId,
            String modId,
            boolean enabled,
            String displayName) {
        if (!enabled) {
            providers.add(inactiveViewer(
                    sourceId,
                    "disabled_by_config",
                    displayName + " recipe capture is disabled"));
            return;
        }
        RecipeKnowledgeProvider provider = registered.stream()
                .filter(value -> value.sourceId().equals(sourceId))
                .findFirst()
                .orElse(null);
        if (provider != null) {
            providers.add(provider);
            return;
        }
        providers.add(inactiveViewer(
                sourceId,
                platform.isModLoaded(modId) ? "plugin_unavailable" : "mod_not_loaded",
                platform.isModLoaded(modId)
                        ? displayName + " plugin has not initialized"
                        : displayName + " is not installed"));
    }

    private static RecipeKnowledgeProvider inactiveViewer(
            String sourceId, String code, String message) {
        return new RecipeKnowledgeProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public RecipeProviderSnapshot capture() {
                return RecipeProviderSnapshot.unavailable(sourceId, code, message);
            }
        };
    }

    private RecipeProviderSnapshot vanillaRecipes(
            LocalPlayer player, Minecraft client, Instant capturedAt) {
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
                        DataCompleteness.PARTIAL,
                        capturedAt,
                        "minecraft:client_recipe_book",
                        "minecraft:client_recipe_display");
                recipes.add(new RecipeEntrySnapshot(
                        new RecipeReference(
                                "minecraft:client_recipe_book",
                                CAPTURE_GENERATION_PLACEHOLDER,
                                recipeId),
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
                        RecipeUnlockState.UNLOCKED,
                        recipeEvidence));
            }
        }
        recipes.sort(Comparator.comparing(RecipeEntrySnapshot::id));
        return RecipeProviderSnapshot.available(
                "minecraft:client_recipe_book",
                DataCompleteness.PARTIAL,
                recipes,
                List.of(new dev.tomewisp.recipe.RecipeProviderDiagnostic(
                        "minecraft:client_recipe_book",
                        "recipe_book_only",
                        "Only synchronized unlocked recipe-book entries are visible")));
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
