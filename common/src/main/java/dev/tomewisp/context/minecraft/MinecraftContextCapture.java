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
import dev.tomewisp.context.game.ObservableGameStateSnapshot;
import dev.tomewisp.context.game.ObservableGameStateSnapshot.DiagnosticValue;
import dev.tomewisp.context.game.ObservableGameStateSnapshot.SectionDiagnostic;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.platform.PlatformServices;
import dev.tomewisp.recipe.RecipeKnowledgeProvider;
import dev.tomewisp.recipe.RecipeKnowledgeService;
import dev.tomewisp.recipe.RecipeProviderSnapshot;
import dev.tomewisp.recipe.RecipeVisibilityPolicy;
import dev.tomewisp.tool.gamestate.WorldQueryOperation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final String CAPTURE_GENERATION_PLACEHOLDER = "0".repeat(64);
    private static final String REGISTRY_PROVENANCE = "minecraft:registry";
    private static final String RECIPE_PROVENANCE = "minecraft:recipe_manager";
    private final Gson gson;
    private final PlatformService platform;
    private final RecipeKnowledgeService recipeKnowledge = new RecipeKnowledgeService();

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
        Optional<ObservableGameStateSnapshot> observableGameState =
                capabilities.contains(ContextCapability.OBSERVABLE_GAME_STATE)
                        ? Optional.of(captureObservableGameState(
                                source, serverPlayer, capturedAt,
                                player.orElseGet(() -> serverPlayer == null
                                        ? null
                                        : capturePlayer(serverPlayer, capturedAt))))
                        : Optional.empty();

        long bytes = serializedBytes(caller)
                + serializedBytes(player.orElse(null))
                + serializedBytes(registries.orElse(null))
                + serializedBytes(recipes.orElse(null))
                + serializedBytes(observableGameState.orElse(null));
        ContextMetrics metrics = new ContextMetrics(
                registries.map(value -> (long) value.entries().size()).orElse(0L),
                recipes.map(value -> (long) value.recipes().size()).orElse(0L),
                player.map(value -> (long) value.inventory().slots().size()).orElse(0L),
                bytes,
                System.nanoTime() - started);
        return new ToolInvocationContext(
                correlationId, capturedAt, caller, player, registries, recipes,
                observableGameState, metrics);
    }

    private ObservableGameStateSnapshot captureObservableGameState(
            CommandSourceStack source,
            ServerPlayer serverPlayer,
            Instant capturedAt,
            PlayerSnapshot player) {
        EvidenceMetadata unavailable = evidence(
                DataCompleteness.UNKNOWN, capturedAt,
                "minecraft:server_client_state", "minecraft:server_topology_boundary");
        List<SectionDiagnostic> clientOnly = List.of(new SectionDiagnostic(
                "client_state_not_visible_to_server",
                "This request has no available player-client observation for client-owned menus or configuration"));
        // Loader metadata on a server is not the player's client-visible mod list. Do not expose
        // server-only contacts, dependencies, licences, or private implementation metadata to an
        // ordinary player merely because their model happens to be server hosted.
        List<dev.tomewisp.platform.InstalledModMetadata> mods = List.of();
        DataCompleteness modCompleteness = DataCompleteness.UNKNOWN;
        List<SectionDiagnostic> modDiagnostics = List.of(new SectionDiagnostic(
                "client_mod_list_not_visible_to_server",
                "The server cannot attest the player's installed client mods"));
        EvidenceMetadata playerEvidence = serverPlayer == null
                ? unavailable
                : evidence(
                        DataCompleteness.COMPLETE, capturedAt,
                        "minecraft:server_player", "minecraft:server_player");
        List<DiagnosticValue> diagnostics = serverPlayer == null
                ? List.of()
                : List.of(
                        new DiagnosticValue("position", "x", Double.toString(serverPlayer.getX())),
                        new DiagnosticValue("position", "y", Double.toString(serverPlayer.getY())),
                        new DiagnosticValue("position", "z", Double.toString(serverPlayer.getZ())),
                        new DiagnosticValue("position", "dimension", player.dimension()),
                        new DiagnosticValue("player", "game_mode", player.gameMode()));
        var level = source.getLevel();
        var border = level.getWorldBorder();
        var spawn = level.getRespawnData();
        Map<String, ObservableGameStateSnapshot.QueryValue> queries = new LinkedHashMap<>();
        if (authorizedWorldQuery(source, WorldQueryOperation.TIME)) {
            queries.put("time", serverQuery("time", Long.toString(level.getGameTime())));
        }
        if (authorizedWorldQuery(source, WorldQueryOperation.WEATHER)) {
            queries.put("weather", serverQuery("weather",
                    level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear"));
        }
        if (authorizedWorldQuery(source, WorldQueryOperation.DIFFICULTY)) {
            queries.put("difficulty", serverQuery(
                    "difficulty", level.getDifficulty().getSerializedName()));
        }
        if (authorizedWorldQuery(source, WorldQueryOperation.WORLD_BORDER)) {
            queries.put("world_border", serverQuery("world_border",
                    "center=" + border.getCenterX() + "," + border.getCenterZ()
                            + ",size=" + border.getSize()));
        }
        if (authorizedWorldQuery(source, WorldQueryOperation.SPAWN)) {
            queries.put("spawn", serverQuery("spawn",
                    spawn.dimension().identifier() + " " + spawn.pos().toShortString()));
        }
        boolean worldQueriesAuthorized = queries.size() == WorldQueryOperation.values().length;
        return new ObservableGameStateSnapshot(
                capturedAt,
                new ObservableGameStateSnapshot.RuntimeState(
                        platform.gameVersion(), platform.platformName(),
                        platform.isDevelopmentEnvironment(), "server",
                        evidence(DataCompleteness.COMPLETE, capturedAt,
                                "tomewisp:observable_runtime", "minecraft:server_runtime"),
                        List.of()),
                new ObservableGameStateSnapshot.ModsState(
                        mods,
                        evidence(modCompleteness, capturedAt,
                                "tomewisp:installed_mods", "tomewisp:loader_metadata"),
                        modDiagnostics),
                new ObservableGameStateSnapshot.OptionsState(List.of(), unavailable, clientOnly),
                new ObservableGameStateSnapshot.PacksState(List.of(), List.of(), unavailable, clientOnly),
                new ObservableGameStateSnapshot.ShaderState(
                        false, "client_only", "", Map.of(), unavailable, clientOnly),
                new ObservableGameStateSnapshot.DiagnosticsState(
                        diagnostics, playerEvidence,
                        serverPlayer == null
                                ? List.of(new SectionDiagnostic(
                                        "player_required",
                                        "F3-style diagnostics require a player source"))
                                : List.of(new SectionDiagnostic(
                                        "client_f3_metrics_unavailable",
                                        "Server capture includes authoritative player state but not client renderer metrics"))),
                new ObservableGameStateSnapshot.PlayerUiState(
                        player,
                        serverPlayer == null
                                ? "unavailable"
                                : serverPlayer.containerMenu == serverPlayer.inventoryMenu
                                ? "gameplay_or_player_inventory"
                                : "open_synchronized_menu",
                        serverPlayer == null
                                || serverPlayer.containerMenu == serverPlayer.inventoryMenu
                                        || serverPlayer.containerMenu.getType() == null
                                ? ""
                                : BuiltInRegistries.MENU.getKey(
                                        serverPlayer.containerMenu.getType()).toString(),
                        playerEvidence,
                        serverPlayer == null
                                ? List.of(new SectionDiagnostic(
                                        "player_required",
                                        "No player is associated with this server command source"))
                                : clientOnly),
                new ObservableGameStateSnapshot.WorldQueriesState(
                        queries,
                        evidence(worldQueriesAuthorized
                                        ? DataCompleteness.COMPLETE
                                        : DataCompleteness.UNKNOWN,
                                capturedAt,
                                "minecraft:server_world_queries", "minecraft:server_level_state"),
                        worldQueriesAuthorized
                                ? List.of()
                                : List.of(new SectionDiagnostic(
                                        "permission_required",
                                        "Read-only server world queries require game-master command permission"))));
    }

    private static boolean authorizedWorldQuery(
            CommandSourceStack source, WorldQueryOperation operation) {
        Objects.requireNonNull(operation, "operation");
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static ObservableGameStateSnapshot.QueryValue serverQuery(
            String operation, String value) {
        return new ObservableGameStateSnapshot.QueryValue(
                operation, value, true, "server_authoritative");
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
        RecipeKnowledgeProvider recipeManager = new RecipeKnowledgeProvider() {
            @Override
            public String sourceId() {
                return "minecraft:recipe_manager";
            }

            @Override
            public RecipeProviderSnapshot capture() {
                return captureRecipeManager(source, capturedAt);
            }
        };
        return recipeKnowledge.capture(
                evidence(
                        DataCompleteness.UNKNOWN,
                        capturedAt,
                        "minecraft:recipe_manager",
                        RECIPE_PROVENANCE),
                RecipeVisibilityPolicy.ALL_KNOWN,
                List.of(recipeManager));
    }

    private RecipeProviderSnapshot captureRecipeManager(
            CommandSourceStack source, java.time.Instant capturedAt) {
        ContextMap displayContext = SlotDisplayContext.fromLevel(source.getLevel());
        List<RecipeEntrySnapshot> recipes = source.getServer().getRecipeManager().getRecipes()
                .stream()
                .map(holder -> captureRecipe(holder, displayContext, capturedAt))
                .sorted(Comparator.comparing(RecipeEntrySnapshot::id))
                .toList();
        return RecipeProviderSnapshot.available(
                "minecraft:recipe_manager", DataCompleteness.COMPLETE, recipes, List.of());
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
                new RecipeReference(
                        "minecraft:recipe_manager", CAPTURE_GENERATION_PLACEHOLDER, recipeId),
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
