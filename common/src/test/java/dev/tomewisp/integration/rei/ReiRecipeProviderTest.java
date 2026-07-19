package dev.tomewisp.integration.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.architectury.fluid.FluidStack;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.recipe.RecipeProviderSnapshot;
import dev.tomewisp.recipe.RecipeProviderState;
import dev.tomewisp.recipe.RecipeUnlockState;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

final class ReiRecipeProviderTest {
    private static final Identifier CATEGORY_ID =
            Identifier.fromNamespaceAndPath("test", "crafting");
    private static final CategoryIdentifier<Display> CATEGORY = category(CATEGORY_ID);
    private static final CategoryIdentifier<Display> TAG_CATEGORY = category(
            Identifier.fromNamespaceAndPath("minecraft", "plugins/tag"));

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bindName(Items.GOLD_INGOT, "Gold Ingot");
        bindName(Items.GOLD_BLOCK, "Gold Block");
    }

    @Test
    void detachesGroupedEntriesAndDisplayLocationThroughPublicApi() {
        Display display = display(
                Identifier.fromNamespaceAndPath("test", "gold_block"),
                ingredient(new ItemStack(Items.GOLD_INGOT, 9)),
                ingredient(new ItemStack(Items.GOLD_BLOCK)));

        RecipeProviderSnapshot snapshot = provider(registry(Map.of(CATEGORY, List.of(display))))
                .capture();

        assertEquals(RecipeProviderState.AVAILABLE, snapshot.state());
        assertEquals(DataCompleteness.COMPLETE, snapshot.completeness(), snapshot.diagnostics().toString());
        assertEquals(1, snapshot.recipes().size());
        assertEquals("test:gold_block", snapshot.recipes().getFirst().id());
        assertEquals("test:crafting", snapshot.recipes().getFirst().type());
        assertEquals(9, snapshot.recipes().getFirst().ingredients().getFirst().count());
        assertEquals("minecraft:gold_block",
                snapshot.recipes().getFirst().outputs().getFirst().stack().itemId());
        assertEquals(RecipeUnlockState.UNKNOWN, snapshot.recipes().getFirst().unlockState());
        assertEquals(64, snapshot.generation().length());
        assertTrue(snapshot.recipes().getFirst().reference().recipeId()
                .startsWith("tomewisp:rei/test/crafting/test/gold_block/"));
    }

    @Test
    void omitsComponentBearingDisplaysAndMarksProviderPartial() {
        ItemStack input = new ItemStack(Items.GOLD_INGOT);
        input.set(DataComponents.CUSTOM_NAME, Component.literal("special"));
        Display display = display(
                Identifier.fromNamespaceAndPath("test", "component_recipe"),
                ingredient(input),
                ingredient(new ItemStack(Items.GOLD_BLOCK)));

        RecipeProviderSnapshot snapshot = provider(registry(Map.of(CATEGORY, List.of(display))))
                .capture();

        assertEquals(DataCompleteness.PARTIAL, snapshot.completeness());
        assertTrue(snapshot.recipes().isEmpty());
        assertEquals("item_components_unsupported", snapshot.diagnostics().getFirst().code());
    }

    @Test
    void omitsReiTagMembershipDisplaysWithoutDegradingRecipeCompleteness() {
        Display tagDisplay = display(
                Identifier.fromNamespaceAndPath("minecraft", "storage_blocks/iron"),
                ingredient(new ItemStack(Items.GOLD_INGOT)),
                ingredient(new ItemStack(Items.GOLD_BLOCK)));

        RecipeProviderSnapshot snapshot = provider(registry(Map.of(
                        TAG_CATEGORY, List.of(tagDisplay))))
                .capture();

        assertEquals(DataCompleteness.COMPLETE, snapshot.completeness());
        assertTrue(snapshot.recipes().isEmpty());
        assertTrue(snapshot.diagnostics().isEmpty());
    }

    @Test
    void capturesFluidInputAndCreatesStableFallbackId() {
        Display display = display(
                null,
                ingredientValue(FluidStack.create(Fluids.WATER, 1000L)),
                ingredient(new ItemStack(Items.GOLD_BLOCK)));

        RecipeProviderSnapshot snapshot = provider(registry(Map.of(CATEGORY, List.of(display))))
                .capture();

        assertEquals(DataCompleteness.COMPLETE, snapshot.completeness(), snapshot.diagnostics().toString());
        assertEquals(1, snapshot.recipes().size());
        assertEquals("minecraft:water", snapshot.recipes().getFirst().fluids().getFirst().fluidId());
        assertEquals(1000L, snapshot.recipes().getFirst().fluids().getFirst().amount());
        assertTrue(snapshot.recipes().getFirst().id().startsWith("tomewisp:rei_fallback/"));
        assertTrue(snapshot.recipes().getFirst().reference().recipeId()
                .startsWith("tomewisp:rei/test/crafting/generated/"));
    }

    @Test
    void unavailableRegistryAndExactNavigationFailExplicitly() {
        RecipeProviderSnapshot unavailable = new ReiRecipeProvider(
                Instant.EPOCH,
                platform(),
                () -> {
                    throw new IllegalStateException("fixture unavailable");
                }).capture();

        assertEquals(RecipeProviderState.UNAVAILABLE, unavailable.state());
        assertEquals("runtime_unavailable", unavailable.diagnostics().getFirst().code());

        ReiRecipeNavigator navigator = new ReiRecipeNavigator();
        assertFalse(navigator.supportsExactRecipe());
        var result = navigator.openExact(new RecipeReference(
                "viewer:rei", "0".repeat(64), "test:recipe"));
        assertFalse(result.opened());
        assertEquals("exact_unsupported", result.code());
    }

    private static ReiRecipeProvider provider(DisplayRegistry registry) {
        return new ReiRecipeProvider(Instant.EPOCH, platform(), () -> registry);
    }

    private static DisplayRegistry registry(Map<CategoryIdentifier<?>, List<Display>> displays) {
        return proxy(DisplayRegistry.class, (self, method, args) -> switch (method.getName()) {
            case "getAll" -> displays;
            case "size" -> displays.values().stream().mapToInt(List::size).sum();
            default -> defaultValue(method.getReturnType());
        });
    }

    private static CategoryIdentifier<Display> category(Identifier id) {
        return proxy(CategoryIdentifier.class, (self, method, args) -> switch (method.getName()) {
            case "getIdentifier" -> id;
            case "getNamespace" -> id.getNamespace();
            case "getPath" -> id.getPath();
            case "toString" -> id.toString();
            case "hashCode" -> id.hashCode();
            case "equals" -> self == args[0];
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Display display(
            Identifier location, EntryIngredient input, EntryIngredient output) {
        return proxy(Display.class, (self, method, args) -> switch (method.getName()) {
            case "getInputEntries" -> List.of(input);
            case "getOutputEntries" -> List.of(output);
            case "getCategoryIdentifier" -> CATEGORY;
            case "getDisplayLocation" -> Optional.ofNullable(location);
            default -> defaultValue(method.getReturnType());
        });
    }

    private static EntryIngredient ingredient(ItemStack... stacks) {
        List<EntryStack<?>> entries = Stream.of(stacks)
                .<EntryStack<?>>map(ReiRecipeProviderTest::entry)
                .toList();
        return ingredient(entries);
    }

    private static EntryIngredient ingredientValue(Object value) {
        return ingredient(List.of(entryValue(value)));
    }

    private static EntryIngredient ingredient(List<EntryStack<?>> entries) {
        return proxy(EntryIngredient.class, (self, method, args) -> switch (method.getName()) {
            case "isEmpty" -> entries.isEmpty();
            case "size" -> entries.size();
            case "get" -> entries.get((int) args[0]);
            case "iterator" -> entries.iterator();
            case "stream" -> entries.stream();
            default -> defaultValue(method.getReturnType());
        });
    }

    private static EntryStack<ItemStack> entry(ItemStack stack) {
        return entryValue(stack);
    }

    @SuppressWarnings("unchecked")
    private static <T> EntryStack<T> entryValue(T value) {
        return proxy(EntryStack.class, (self, method, args) -> switch (method.getName()) {
            case "getValue" -> value;
            case "isEmpty" -> value instanceof ItemStack stack && stack.isEmpty();
            case "getIdentifier" -> value instanceof ItemStack stack
                    ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                    : Identifier.fromNamespaceAndPath("test", "fluid");
            default -> defaultValue(method.getReturnType());
        });
    }

    private static void bindName(Item item, String name) {
        item.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
                .set(DataComponents.ITEM_NAME, Component.literal(name))
                .build());
    }

    private static PlatformService platform() {
        return new PlatformService() {
            @Override public String platformName() { return "common-test"; }
            @Override public String gameVersion() { return "test"; }
            @Override public boolean isModLoaded(String modId) {
                return modId.equals("roughlyenoughitems");
            }
            @Override public boolean isDevelopmentEnvironment() { return true; }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
