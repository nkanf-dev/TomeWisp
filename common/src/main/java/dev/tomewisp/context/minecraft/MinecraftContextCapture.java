package dev.tomewisp.context.minecraft;

import com.google.gson.Gson;
import dev.tomewisp.context.BlockPositionSnapshot;
import dev.tomewisp.context.CallerKind;
import dev.tomewisp.context.CallerSnapshot;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ContextMetrics;
import dev.tomewisp.context.IngredientSlotSnapshot;
import dev.tomewisp.context.InventorySlotSnapshot;
import dev.tomewisp.context.ItemStackSnapshot;
import dev.tomewisp.context.PlayerSnapshot;
import dev.tomewisp.context.RecipeEntrySnapshot;
import dev.tomewisp.context.RecipeSnapshot;
import dev.tomewisp.context.RegistryEntrySnapshot;
import dev.tomewisp.context.RegistrySnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

public final class MinecraftContextCapture {
    private static final String REGISTRY_PROVENANCE = "minecraft:registry";
    private static final String RECIPE_PROVENANCE = "minecraft:recipe_manager";
    private final Gson gson;

    public MinecraftContextCapture(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
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
                ? Optional.of(capturePlayer(serverPlayer))
                : Optional.empty();
        Optional<RegistrySnapshot> registries = capabilities.contains(ContextCapability.REGISTRIES)
                ? Optional.of(captureRegistries())
                : Optional.empty();
        Optional<RecipeSnapshot> recipes = capabilities.contains(ContextCapability.RECIPES)
                ? Optional.of(captureRecipes(source))
                : Optional.empty();

        long bytes = serializedBytes(caller)
                + serializedBytes(player.orElse(null))
                + serializedBytes(registries.orElse(null))
                + serializedBytes(recipes.orElse(null));
        ContextMetrics metrics = new ContextMetrics(
                registries.map(value -> (long) value.entries().size()).orElse(0L),
                recipes.map(value -> (long) value.recipes().size()).orElse(0L),
                player.map(value -> (long) value.inventory().size()).orElse(0L),
                bytes,
                System.nanoTime() - started);
        return new ToolInvocationContext(
                correlationId, Instant.now(), caller, player, registries, recipes, metrics);
    }

    private PlayerSnapshot capturePlayer(ServerPlayer player) {
        List<InventorySlotSnapshot> inventory = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            inventory.add(new InventorySlotSnapshot(
                    slot, captureStack(player.getInventory().getItem(slot))));
        }
        BlockPos position = player.blockPosition();
        return new PlayerSnapshot(
                player.getUUID(),
                player.getName().getString(),
                player.level().dimension().identifier().toString(),
                new BlockPositionSnapshot(position.getX(), position.getY(), position.getZ()),
                player.gameMode().getName(),
                captureStack(player.getMainHandItem()),
                captureStack(player.getOffhandItem()),
                inventory);
    }

    private RegistrySnapshot captureRegistries() {
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
        return new RegistrySnapshot(entries);
    }

    private RecipeSnapshot captureRecipes(CommandSourceStack source) {
        ContextMap displayContext = SlotDisplayContext.fromLevel(source.getLevel());
        List<RecipeEntrySnapshot> recipes = source.getServer().getRecipeManager().getRecipes()
                .stream()
                .map(holder -> captureRecipe(holder, displayContext))
                .sorted(Comparator.comparing(RecipeEntrySnapshot::id))
                .toList();
        return new RecipeSnapshot(recipes);
    }

    private RecipeEntrySnapshot captureRecipe(
            RecipeHolder<?> holder, ContextMap displayContext) {
        List<IngredientSlotSnapshot> ingredients = holder.value().placementInfo().ingredients()
                .stream()
                .map(this::captureIngredient)
                .toList();
        List<ItemStackSnapshot> outputs = holder.value().display().stream()
                .flatMap(display -> display.result().resolveForStacks(displayContext).stream())
                .map(this::captureStack)
                .toList();
        Identifier type = Objects.requireNonNull(
                BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()));
        return new RecipeEntrySnapshot(
                holder.id().identifier().toString(),
                type.toString(),
                ingredients,
                outputs,
                RECIPE_PROVENANCE);
    }

    private IngredientSlotSnapshot captureIngredient(Ingredient ingredient) {
        List<String> alternatives = ingredient.items()
                .map(holder -> holder.unwrapKey()
                        .orElseThrow(() -> new IllegalStateException("Unbound recipe item"))
                        .identifier()
                        .toString())
                .toList();
        return new IngredientSlotSnapshot(alternatives);
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
}
