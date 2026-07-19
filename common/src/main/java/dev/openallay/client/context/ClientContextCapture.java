package dev.openallay.client.context;

import com.google.gson.Gson;
import dev.openallay.context.BlockPositionSnapshot;
import dev.openallay.context.CallerKind;
import dev.openallay.context.CallerSnapshot;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ContextMetrics;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.IngredientAlternativeSnapshot;
import dev.openallay.context.IngredientRequirementSnapshot;
import dev.openallay.context.InventorySnapshot;
import dev.openallay.context.InventorySlotSnapshot;
import dev.openallay.context.ItemStackSnapshot;
import dev.openallay.context.PlayerSnapshot;
import dev.openallay.context.RecipeEntrySnapshot;
import dev.openallay.context.RecipeLayoutSnapshot;
import dev.openallay.context.RecipeOutputSnapshot;
import dev.openallay.context.RecipeProcessingSnapshot;
import dev.openallay.context.RecipeReference;
import dev.openallay.context.RecipeSnapshot;
import dev.openallay.context.RegistrySnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.minecraft.RegistryCatalogCapture;
import dev.openallay.context.game.ObservableGameStateSnapshot;
import dev.openallay.context.game.ObservableGameStateSnapshot.DiagnosticValue;
import dev.openallay.context.game.ObservableGameStateSnapshot.OptionValue;
import dev.openallay.context.game.ObservableGameStateSnapshot.QueryValue;
import dev.openallay.context.game.ObservableGameStateSnapshot.SectionDiagnostic;
import dev.openallay.platform.PlatformService;
import dev.openallay.platform.PlatformServices;
import dev.openallay.recipe.RecipeKnowledgeProvider;
import dev.openallay.recipe.RecipeKnowledgeService;
import dev.openallay.recipe.RecipeProviderSnapshot;
import dev.openallay.recipe.RecipeProviderReadiness;
import dev.openallay.recipe.RecipeProviderReadinessGate;
import dev.openallay.recipe.RecipeUnlockState;
import dev.openallay.recipe.RecipeViewerProviderRegistry;
import dev.openallay.recipe.config.RecipeClientConfig;
import dev.openallay.recipe.config.RecipeClientRuntime;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

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
                ? Optional.of(registries(client, capturedAt))
                : Optional.empty();
        Optional<RecipeSnapshot> recipes = capabilities.contains(ContextCapability.RECIPES)
                ? Optional.of(recipes(player, client, capturedAt))
                : Optional.empty();
        Optional<ObservableGameStateSnapshot> observableGameState =
                capabilities.contains(ContextCapability.OBSERVABLE_GAME_STATE)
                        ? Optional.of(observableGameState(player, client, capturedAt,
                                playerSnapshot.orElseGet(() -> player(player, client, capturedAt))))
                        : Optional.empty();
        long bytes = bytes(caller)
                + bytes(playerSnapshot.orElse(null))
                + bytes(registries.orElse(null))
                + bytes(recipes.orElse(null))
                + bytes(observableGameState.orElse(null));
        return new ToolInvocationContext(
                correlationId,
                capturedAt,
                caller,
                playerSnapshot,
                registries,
                recipes,
                observableGameState,
                new ContextMetrics(
                        registries.map(value -> (long) value.entries().size()).orElse(0L),
                        recipes.map(value -> (long) value.recipes().size()).orElse(0L),
                        playerSnapshot.map(value -> (long) value.inventory().slots().size())
                                .orElseGet(() -> observableGameState
                                        .map(value -> (long) value.player().player().inventory().slots().size())
                                        .orElse(0L)),
                        bytes,
                        System.nanoTime() - started));
    }

    private ObservableGameStateSnapshot observableGameState(
            LocalPlayer player,
            Minecraft client,
            Instant capturedAt,
            PlayerSnapshot playerSnapshot) {
        return new ObservableGameStateSnapshot(
                capturedAt,
                runtimeState(client, capturedAt),
                modsState(capturedAt),
                optionsState(client, capturedAt),
                packsState(client, capturedAt),
                shaderState(capturedAt),
                diagnosticsState(player, client, capturedAt),
                playerUiState(client, playerSnapshot, capturedAt),
                worldQueriesState(client, capturedAt));
    }

    private ObservableGameStateSnapshot.RuntimeState runtimeState(
            Minecraft client, Instant capturedAt) {
        String connectionKind = client.hasSingleplayerServer()
                ? "singleplayer"
                : client.getConnection() == null ? "disconnected" : "multiplayer";
        return new ObservableGameStateSnapshot.RuntimeState(
                platform.gameVersion(),
                platform.platformName(),
                platform.isDevelopmentEnvironment(),
                connectionKind,
                evidence(DataCompleteness.COMPLETE, capturedAt,
                        "openallay:observable_runtime", "minecraft:client_runtime"),
                List.of());
    }

    private ObservableGameStateSnapshot.ModsState modsState(Instant capturedAt) {
        List<dev.openallay.platform.InstalledModMetadata> installed;
        DataCompleteness completeness;
        List<SectionDiagnostic> diagnostics;
        try {
            installed = platform.installedMods();
            completeness = DataCompleteness.COMPLETE;
            diagnostics = List.of();
        } catch (RuntimeException unavailable) {
            installed = List.of();
            completeness = DataCompleteness.UNKNOWN;
            diagnostics = List.of(new SectionDiagnostic(
                    "loader_metadata_unavailable",
                    "The active loader did not provide public installed-mod metadata"));
        }
        return new ObservableGameStateSnapshot.ModsState(installed, new EvidenceMetadata(
                DataAuthority.INTEGRATION_API,
                completeness,
                capturedAt,
                "openallay:installed_mods",
                "openallay:loader_metadata",
                platform.gameVersion(),
                platform.platformName().toLowerCase(Locale.ROOT),
                Map.of()), diagnostics);
    }

    private ObservableGameStateSnapshot.OptionsState optionsState(
            Minecraft client, Instant capturedAt) {
        List<OptionValue> values = new ArrayList<>();
        for (String line : client.options.dumpOptionsForReport().lines().toList()) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (!safeOptionKey(key)) {
                continue;
            }
            values.add(new OptionValue(optionGroup(key), key, key, value));
        }
        for (var mapping : client.options.keyMappings) {
            values.add(new OptionValue(
                    "controls",
                    mapping.getName(),
                    mapping.getName(),
                    mapping.getTranslatedKeyMessage().getString()));
        }
        values.sort(Comparator.comparing(OptionValue::group).thenComparing(OptionValue::key));
        return new ObservableGameStateSnapshot.OptionsState(
                values,
                evidence(DataCompleteness.PARTIAL, capturedAt,
                        "minecraft:client_options", "minecraft:options_report"),
                List.of(new SectionDiagnostic(
                        "mod_option_adapters_not_registered",
                        "Vanilla options and key mappings are complete for the public report; mod-owned configuration screens require explicit public adapters")));
    }

    private static boolean safeOptionKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return !lower.contains("token")
                && !lower.contains("password")
                && !lower.contains("secret")
                && !lower.contains("lastserver")
                && !lower.contains("serveraddress")
                && !lower.contains("proxy");
    }

    private static String optionGroup(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("key_")) return "controls";
        if (lower.contains("sound") || lower.contains("music") || lower.contains("subtitle")) return "sound";
        if (lower.contains("chat") || lower.contains("language") || lower.contains("unicode")) return "language_chat";
        if (lower.contains("mouse") || lower.contains("sensitivity") || lower.contains("invert")) return "mouse";
        if (lower.contains("telemetry") || lower.contains("presence") || lower.contains("online")
                || lower.contains("realms") || lower.contains("serverlisting")) return "online_privacy";
        if (lower.contains("narrator") || lower.contains("access") || lower.contains("contrast")
                || lower.contains("effectscale") || lower.contains("damageTilt".toLowerCase(Locale.ROOT))) return "accessibility";
        if (lower.contains("render") || lower.contains("graphics") || lower.contains("distance")
                || lower.contains("cloud") || lower.contains("framerate") || lower.contains("fullscreen")
                || lower.contains("mipmap") || lower.contains("vsync") || lower.contains("gamma")
                || lower.contains("particle") || lower.contains("biomeblend") || lower.contains("vignette")) return "video";
        if (lower.contains("resourcepack")) return "packs";
        return "general";
    }

    private ObservableGameStateSnapshot.PacksState packsState(
            Minecraft client, Instant capturedAt) {
        Set<String> selected = Set.copyOf(client.getResourcePackRepository().getSelectedIds());
        List<ObservableGameStateSnapshot.PackInfo> packs = client.getResourcePackRepository()
                .getAvailablePacks().stream()
                .map(pack -> new ObservableGameStateSnapshot.PackInfo(
                        pack.getId(),
                        pack.getTitle().getString(),
                        pack.getDescription().getString(),
                        selected.contains(pack.getId()),
                        pack.isRequired(),
                        pack.getCompatibility().toString(),
                        pack.getPackSource().toString()))
                .sorted(Comparator.comparing(ObservableGameStateSnapshot.PackInfo::id))
                .toList();
        return new ObservableGameStateSnapshot.PacksState(
                packs,
                List.of(),
                evidence(DataCompleteness.PARTIAL, capturedAt,
                        "minecraft:client_packs", "minecraft:resource_pack_repository"),
                List.of(new SectionDiagnostic(
                        "data_pack_state_not_synchronized",
                        "The client has no complete public view of server data-pack selection")));
    }

    private ObservableGameStateSnapshot.ShaderState shaderState(Instant capturedAt) {
        boolean irisLoaded = platform.isModLoaded("iris");
        String code = irisLoaded ? "public_shader_adapter_unavailable" : "shader_mod_not_loaded";
        String message = irisLoaded
                ? "A compatible public shader configuration adapter is not available"
                : "No compatible shader-pack provider is installed";
        return new ObservableGameStateSnapshot.ShaderState(
                false,
                irisLoaded ? "iris" : "none",
                "",
                Map.of(),
                evidence(DataCompleteness.UNKNOWN, capturedAt,
                        "openallay:shader_state", "openallay:optional_shader_adapter"),
                List.of(new SectionDiagnostic(code, message)));
    }

    private ObservableGameStateSnapshot.DiagnosticsState diagnosticsState(
            LocalPlayer player, Minecraft client, Instant capturedAt) {
        List<DiagnosticValue> values = new ArrayList<>();
        add(values, "position", "x", Double.toString(player.getX()));
        add(values, "position", "y", Double.toString(player.getY()));
        add(values, "position", "z", Double.toString(player.getZ()));
        add(values, "position", "block", player.blockPosition().toShortString());
        add(values, "position", "dimension", player.level().dimension().identifier().toString());
        add(values, "position", "direction", player.getDirection().getName());
        add(values, "position", "yaw", Float.toString(player.getYRot()));
        add(values, "position", "pitch", Float.toString(player.getXRot()));
        player.level().getBiome(player.blockPosition()).unwrapKey().ifPresent(key ->
                add(values, "position", "biome", key.identifier().toString()));

        Runtime runtime = Runtime.getRuntime();
        add(values, "performance", "fps", Integer.toString(client.getFps()));
        add(values, "performance", "frame_time_ns", Long.toString(client.getFrameTimeNs()));
        add(values, "performance", "gpu_utilization", Double.toString(client.getGpuUtilization()));
        add(values, "performance", "heap_used_bytes",
                Long.toString(runtime.totalMemory() - runtime.freeMemory()));
        add(values, "performance", "heap_max_bytes", Long.toString(runtime.maxMemory()));
        add(values, "renderer", "chunk_source", client.level.gatherChunkSourceStats());
        add(values, "renderer", "render_distance",
                Integer.toString(client.options.getEffectiveRenderDistance()));
        add(values, "renderer", "simulation_distance",
                Integer.toString(client.options.simulationDistance().get()));
        add(values, "renderer", "entities", Integer.toString(client.level.getEntityCount()));
        add(values, "player", "health", Float.toString(player.getHealth()));
        add(values, "player", "max_health", Float.toString(player.getMaxHealth()));
        add(values, "player", "food", Integer.toString(player.getFoodData().getFoodLevel()));
        add(values, "player", "air", Integer.toString(player.getAirSupply()));
        add(values, "player", "armor", Integer.toString(player.getArmorValue()));
        add(values, "player", "experience_level", Integer.toString(player.experienceLevel));
        add(values, "player", "selected_hotbar_slot",
                Integer.toString(player.getInventory().getSelectedSlot()));
        add(values, "player", "camera", client.options.getCameraType().name().toLowerCase(Locale.ROOT));
        add(values, "player", "active_effects", player.getActiveEffects().stream()
                .map(effect -> effect.getEffect().unwrapKey()
                        .map(key -> key.identifier().toString())
                        .orElse("unknown"))
                .sorted()
                .toList().toString());

        if (client.hitResult != null) {
            add(values, "target", "type", client.hitResult.getType().name().toLowerCase(Locale.ROOT));
            if (client.hitResult instanceof BlockHitResult blockHit) {
                var state = client.level.getBlockState(blockHit.getBlockPos());
                add(values, "target", "block_id",
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                add(values, "target", "block_position", blockHit.getBlockPos().toShortString());
            } else if (client.hitResult instanceof EntityHitResult entityHit) {
                add(values, "target", "entity_type",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entityHit.getEntity().getType()).toString());
            }
        }
        if (client.getConnection() != null) {
            var info = client.getConnection().getPlayerInfo(player.getUUID());
            if (info != null) {
                add(values, "network", "latency_ms", Integer.toString(info.getLatency()));
            }
        }
        values.sort(Comparator.comparing(DiagnosticValue::category).thenComparing(DiagnosticValue::key));
        return new ObservableGameStateSnapshot.DiagnosticsState(
                values,
                evidence(DataCompleteness.PARTIAL, capturedAt,
                        "minecraft:f3_diagnostics", "minecraft:client_diagnostics"),
                List.of(new SectionDiagnostic(
                        "sampled_client_diagnostics",
                        "Values are a detached sample and may change after capture")));
    }

    private static void add(List<DiagnosticValue> values, String category, String key, String value) {
        values.add(new DiagnosticValue(category, key, value));
    }

    private ObservableGameStateSnapshot.PlayerUiState playerUiState(
            Minecraft client, PlayerSnapshot playerSnapshot, Instant capturedAt) {
        boolean inventoryMenu = client.player.containerMenu == client.player.inventoryMenu;
        String screen = inventoryMenu
                ? "gameplay_or_player_inventory"
                : "open_synchronized_menu";
        String title = inventoryMenu || client.player.containerMenu.getType() == null
                ? ""
                : BuiltInRegistries.MENU.getKey(client.player.containerMenu.getType()).toString();
        return new ObservableGameStateSnapshot.PlayerUiState(
                playerSnapshot,
                screen,
                title,
                evidence(DataCompleteness.PARTIAL, capturedAt,
                        "minecraft:player_ui", "minecraft:client_player_ui"),
                List.of(new SectionDiagnostic(
                        "screen_identity_not_public",
                        "Minecraft exposes the player's synchronized menu state but not every active screen identity")));
    }

    private ObservableGameStateSnapshot.WorldQueriesState worldQueriesState(
            Minecraft client, Instant capturedAt) {
        Map<String, QueryValue> values = new TreeMap<>();
        long gameTime = client.level.getGameTime();
        values.put("time", clientQuery("time",
                "game=" + gameTime + ",day_cycle=" + Math.floorMod(gameTime, 24_000L)));
        values.put("weather", clientQuery("weather",
                client.level.isThundering() ? "thunder" : client.level.isRaining() ? "rain" : "clear"));
        values.put("difficulty", clientQuery(
                "difficulty", client.level.getDifficulty().getSerializedName()));
        var border = client.level.getWorldBorder();
        values.put("world_border", clientQuery("world_border",
                "center=" + border.getCenterX() + "," + border.getCenterZ()
                        + ",size=" + border.getSize()));
        var respawn = client.level.getRespawnData();
        values.put("spawn", clientQuery("spawn",
                respawn.dimension().identifier() + " " + respawn.pos().toShortString()));
        return new ObservableGameStateSnapshot.WorldQueriesState(
                values,
                evidence(DataCompleteness.PARTIAL, capturedAt,
                        "minecraft:client_world_queries", "minecraft:client_level_state"),
                List.of(new SectionDiagnostic(
                        "client_visible_not_server_authoritative",
                        "Query values are synchronized client-visible state, not command execution")));
    }

    private static QueryValue clientQuery(String operation, String value) {
        return new QueryValue(operation, value, false, "client_visible");
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

    private RegistrySnapshot registries(Minecraft client, Instant capturedAt) {
        return new RegistrySnapshot(evidence(
                DataCompleteness.COMPLETE,
                capturedAt,
                "minecraft:client_registry",
                "minecraft:client_registry"),
                RegistryCatalogCapture.capture("minecraft:client_registry", client::isSameThread));
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
                        "openallay:recipe_catalog",
                        "openallay:recipe_catalog"),
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
                String recipeId = "openallay:client_recipe_display/" + entry.id().index();
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
                List.of(new dev.openallay.recipe.RecipeProviderDiagnostic(
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
