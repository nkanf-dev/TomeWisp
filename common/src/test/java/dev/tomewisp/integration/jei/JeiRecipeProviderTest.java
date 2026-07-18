package dev.tomewisp.integration.jei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.recipe.RecipeProviderSnapshot;
import dev.tomewisp.recipe.RecipeProviderState;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IRecipeCategoriesLookup;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

final class JeiRecipeProviderTest {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bindName(Items.AIR, "Air");
        bindName(Items.IRON_INGOT, "Iron Ingot");
        bindName(Items.IRON_BLOCK, "Iron Block");
    }

    private static void bindName(net.minecraft.world.item.Item item, String name) {
        item.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
                .set(DataComponents.ITEM_NAME, Component.literal(name))
                .build());
    }

    @Test
    void detachesHiddenRecipeLayoutsThroughPublicApi() {
        AtomicBoolean hiddenCategories = new AtomicBoolean();
        AtomicBoolean hiddenRecipes = new AtomicBoolean();
        ItemStack input = new ItemStack(Items.IRON_INGOT, 9);
        ItemStack output = new ItemStack(Items.IRON_BLOCK);
        IJeiRuntime runtime = runtime(
                List.of(slot(RecipeIngredientRole.INPUT, input),
                        slot(RecipeIngredientRole.OUTPUT, output)),
                hiddenCategories,
                hiddenRecipes);

        RecipeProviderSnapshot snapshot = new JeiRecipeProvider(
                runtime, Instant.EPOCH, platform()).capture();

        assertEquals(RecipeProviderState.AVAILABLE, snapshot.state());
        assertEquals(DataCompleteness.COMPLETE, snapshot.completeness(), snapshot.diagnostics().toString());
        assertEquals(1, snapshot.recipes().size());
        assertEquals(9, snapshot.recipes().getFirst().ingredients().getFirst().count());
        assertEquals("minecraft:iron_block",
                snapshot.recipes().getFirst().outputs().getFirst().stack().itemId());
        assertEquals(64, snapshot.generation().length());
        assertTrue(hiddenCategories.get());
        assertTrue(hiddenRecipes.get());
    }

    @Test
    void omitsComponentBearingRecipesAndMarksProviderPartial() {
        ItemStack componentInput = new ItemStack(Items.IRON_INGOT);
        componentInput.set(DataComponents.CUSTOM_NAME, Component.literal("special"));
        IJeiRuntime runtime = runtime(
                List.of(slot(RecipeIngredientRole.INPUT, componentInput),
                        slot(RecipeIngredientRole.OUTPUT, new ItemStack(Items.IRON_BLOCK))),
                new AtomicBoolean(),
                new AtomicBoolean());

        RecipeProviderSnapshot snapshot = new JeiRecipeProvider(
                runtime, Instant.EPOCH, platform()).capture();

        assertEquals(DataCompleteness.PARTIAL, snapshot.completeness());
        assertTrue(snapshot.recipes().isEmpty());
        assertEquals("item_components_unsupported",
                snapshot.diagnostics().getFirst().code());
    }

    private static IJeiRuntime runtime(
            List<IRecipeSlotView> slots,
            AtomicBoolean hiddenCategories,
            AtomicBoolean hiddenRecipes) {
        IRecipeType<String> type = proxy(IRecipeType.class, (self, method, args) -> switch (method.getName()) {
            case "getUid" -> Identifier.fromNamespaceAndPath("test", "crafting");
            case "getRecipeClass" -> String.class;
            default -> defaultValue(method.getReturnType());
        });
        IRecipeCategory<String> category = proxy(
                IRecipeCategory.class,
                (self, method, args) -> switch (method.getName()) {
                    case "getRecipeType" -> type;
                    case "getIdentifier", "getRegistryName" ->
                            Identifier.fromNamespaceAndPath("test", "iron_block");
                    case "getTitle" -> Component.literal("Test crafting");
                    case "getWidth", "getHeight" -> 1;
                    default -> defaultValue(method.getReturnType());
                });
        IRecipeCategoriesLookup categories = proxy(
                IRecipeCategoriesLookup.class,
                (self, method, args) -> switch (method.getName()) {
                    case "includeHidden" -> {
                        hiddenCategories.set(true);
                        yield self;
                    }
                    case "limitTypes", "limitFocus" -> self;
                    case "get" -> Stream.of(category);
                    default -> defaultValue(method.getReturnType());
                });
        IRecipeLookup<String> recipes = proxy(
                IRecipeLookup.class,
                (self, method, args) -> switch (method.getName()) {
                    case "includeHidden" -> {
                        hiddenRecipes.set(true);
                        yield self;
                    }
                    case "limitFocus" -> self;
                    case "get" -> Stream.of("fixture");
                    default -> defaultValue(method.getReturnType());
                });
        IRecipeSlotsView slotsView = proxy(
                IRecipeSlotsView.class,
                (self, method, args) -> method.getName().equals("getSlotViews")
                        ? slots
                        : defaultValue(method.getReturnType()));
        IRecipeLayoutDrawable<String> layout = proxy(
                IRecipeLayoutDrawable.class,
                (self, method, args) -> method.getName().equals("getRecipeSlotsView")
                        ? slotsView
                        : defaultValue(method.getReturnType()));
        IRecipeManager manager = proxy(
                IRecipeManager.class,
                (self, method, args) -> switch (method.getName()) {
                    case "createRecipeCategoryLookup" -> categories;
                    case "createRecipeLookup" -> recipes;
                    case "createRecipeLayoutDrawable" -> Optional.of(layout);
                    default -> defaultValue(method.getReturnType());
                });
        IFocusFactory focusFactory = proxy(
                IFocusFactory.class,
                (self, method, args) -> defaultValue(method.getReturnType()));
        IJeiHelpers helpers = proxy(
                IJeiHelpers.class,
                (self, method, args) -> method.getName().equals("getFocusFactory")
                        ? focusFactory
                        : defaultValue(method.getReturnType()));
        return proxy(
                IJeiRuntime.class,
                (self, method, args) -> switch (method.getName()) {
                    case "getRecipeManager" -> manager;
                    case "getJeiHelpers" -> helpers;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static IRecipeSlotView slot(RecipeIngredientRole role, ItemStack stack) {
        ITypedIngredient<ItemStack> ingredient = new ITypedIngredient<>() {
            @Override
            public IIngredientType<ItemStack> getType() {
                return VanillaTypes.ITEM_STACK;
            }

            @Override
            public ItemStack getIngredient() {
                return stack;
            }
        };
        return new IRecipeSlotView() {
            @Override
            public Stream<ITypedIngredient<?>> getAllIngredients() {
                return Stream.of(ingredient);
            }

            @Override
            public List<ITypedIngredient<?>> getAllIngredientsList() {
                return List.of(ingredient);
            }

            @Override
            public Optional<ITypedIngredient<?>> getDisplayedIngredient() {
                return Optional.of(ingredient);
            }

            @Override
            public RecipeIngredientRole getRole() {
                return role;
            }

            @Override
            public void drawHighlight(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int color) {}

            @Override
            public Optional<String> getSlotName() {
                return Optional.empty();
            }
        };
    }

    private static PlatformService platform() {
        return new PlatformService() {
            @Override public String platformName() { return "common-test"; }
            @Override public String gameVersion() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return modId.equals("jei"); }
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
