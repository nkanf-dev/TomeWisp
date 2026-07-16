package dev.tomewisp.client.context;

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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

public final class ClientContextCapture {
    private final Gson gson;

    public ClientContextCapture(Gson gson) {
        this.gson = gson;
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
        CallerSnapshot caller = new CallerSnapshot(
                CallerKind.PLAYER, player.getUUID(), player.getName().getString(), false);
        Optional<PlayerSnapshot> playerSnapshot = capabilities.contains(ContextCapability.PLAYER)
                ? Optional.of(player(player, client))
                : Optional.empty();
        Optional<RegistrySnapshot> registries = capabilities.contains(ContextCapability.REGISTRIES)
                ? Optional.of(registries())
                : Optional.empty();
        Optional<RecipeSnapshot> recipes = capabilities.contains(ContextCapability.RECIPES)
                ? Optional.of(recipes(player, client))
                : Optional.empty();
        long bytes = bytes(caller)
                + bytes(playerSnapshot.orElse(null))
                + bytes(registries.orElse(null))
                + bytes(recipes.orElse(null));
        return new ToolInvocationContext(
                correlationId,
                Instant.now(),
                caller,
                playerSnapshot,
                registries,
                recipes,
                new ContextMetrics(
                        registries.map(value -> (long) value.entries().size()).orElse(0L),
                        recipes.map(value -> (long) value.recipes().size()).orElse(0L),
                        playerSnapshot.map(value -> (long) value.inventory().size()).orElse(0L),
                        bytes,
                        System.nanoTime() - started));
    }

    private PlayerSnapshot player(LocalPlayer player, Minecraft client) {
        List<InventorySlotSnapshot> inventory = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            inventory.add(new InventorySlotSnapshot(slot, stack(player.getInventory().getItem(slot))));
        }
        var position = player.blockPosition();
        String mode = client.gameMode == null || client.gameMode.getPlayerMode() == null
                ? "unknown"
                : client.gameMode.getPlayerMode().getName();
        return new PlayerSnapshot(
                player.getUUID(),
                player.getName().getString(),
                player.level().dimension().identifier().toString(),
                new BlockPositionSnapshot(position.getX(), position.getY(), position.getZ()),
                mode,
                stack(player.getMainHandItem()),
                stack(player.getOffhandItem()),
                inventory);
    }

    private RegistrySnapshot registries() {
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
        return new RegistrySnapshot(entries);
    }

    private RecipeSnapshot recipes(LocalPlayer player, Minecraft client) {
        Set<Integer> seen = new HashSet<>();
        List<RecipeEntrySnapshot> recipes = new ArrayList<>();
        var context = SlotDisplayContext.fromLevel(client.level);
        for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (!seen.add(entry.id().index())) {
                    continue;
                }
                List<IngredientSlotSnapshot> ingredients = entry.craftingRequirements()
                        .orElse(List.of()).stream()
                        .map(ingredient -> new IngredientSlotSnapshot(ingredient.items()
                                .map(holder -> holder.unwrapKey().orElseThrow().identifier().toString())
                                .toList()))
                        .toList();
                List<ItemStackSnapshot> outputs = entry.resultItems(context).stream()
                        .map(this::stack)
                        .toList();
                recipes.add(new RecipeEntrySnapshot(
                        "minecraft:client_display/" + entry.id().index(),
                        entry.display().type().toString(),
                        ingredients,
                        outputs,
                        "minecraft:client_recipe_book"));
            }
        }
        recipes.sort(Comparator.comparing(RecipeEntrySnapshot::id));
        return new RecipeSnapshot(recipes);
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
}
