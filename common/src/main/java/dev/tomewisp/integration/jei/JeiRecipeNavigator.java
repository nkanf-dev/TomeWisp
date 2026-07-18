package dev.tomewisp.integration.jei;

import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.recipe.RecipeNavigationResult;
import dev.tomewisp.recipe.RecipeProviderSnapshot;
import dev.tomewisp.recipe.RecipeViewerNavigator;
import java.time.Instant;
import java.util.List;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

final class JeiRecipeNavigator implements RecipeViewerNavigator {
    @Override
    public String viewerId() {
        return "viewer:jei";
    }

    @Override
    public boolean supportsExactRecipe() {
        return true;
    }

    @Override
    public RecipeNavigationResult openRecipes(String itemId) {
        return openItem(itemId, RecipeIngredientRole.OUTPUT);
    }

    @Override
    public RecipeNavigationResult openUsages(String itemId) {
        return openItem(itemId, RecipeIngredientRole.INPUT);
    }

    @Override
    public RecipeNavigationResult openExact(RecipeReference reference) {
        RecipeNavigationResult readiness = readiness();
        if (readiness != null) {
            return readiness;
        }
        if (reference == null || !reference.sourceId().equals(viewerId())) {
            return RecipeNavigationResult.failed(
                    "invalid_reference", "Recipe reference does not belong to JEI");
        }
        IJeiRuntime runtime = TomeWispJeiPlugin.runtime();
        try {
            JeiRecipeProvider provider = new JeiRecipeProvider(
                    runtime,
                    Instant.now(),
                    dev.tomewisp.platform.PlatformServices.load());
            RecipeProviderSnapshot snapshot = provider.capture();
            if (!reference.generation().equals(snapshot.generation())
                    || snapshot.recipes().stream().noneMatch(recipe ->
                            recipe.reference().equals(reference))) {
                return RecipeNavigationResult.failed(
                        "stale_reference", "Recipe reference is stale");
            }
            return openMatching(runtime, provider, reference.recipeId());
        } catch (RuntimeException failure) {
            return RecipeNavigationResult.failed(
                    "viewer_failure", "JEI could not open the requested recipe");
        }
    }

    private RecipeNavigationResult openItem(String itemId, RecipeIngredientRole role) {
        RecipeNavigationResult readiness = readiness();
        if (readiness != null) {
            return readiness;
        }
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return RecipeNavigationResult.failed("unknown_item", "Item is not registered");
        }
        IJeiRuntime runtime = TomeWispJeiPlugin.runtime();
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        try {
            runtime.getRecipesGui().show(runtime.getJeiHelpers()
                    .getFocusFactory()
                    .createFocus(role, VanillaTypes.ITEM_STACK, stack));
            return RecipeNavigationResult.success();
        } catch (RuntimeException failure) {
            return RecipeNavigationResult.failed(
                    "viewer_failure", "JEI could not open the requested item view");
        }
    }

    private <T> RecipeNavigationResult openCategoryMatch(
            IJeiRuntime runtime,
            JeiRecipeProvider provider,
            IRecipeCategory<T> category,
            String recipeId) {
        List<T> recipes = runtime.getRecipeManager()
                .createRecipeLookup(category.getRecipeType())
                .includeHidden()
                .get()
                .toList();
        for (T recipe : recipes) {
            if (provider.referenceId(category, recipe).equals(recipeId)) {
                runtime.getRecipesGui().showRecipes(category, List.of(recipe), List.of());
                return RecipeNavigationResult.success();
            }
        }
        return null;
    }

    private RecipeNavigationResult openMatching(
            IJeiRuntime runtime, JeiRecipeProvider provider, String recipeId) {
        for (IRecipeCategory<?> category : runtime.getRecipeManager()
                .createRecipeCategoryLookup()
                .includeHidden()
                .get()
                .toList()) {
            RecipeNavigationResult result = openCategoryMatch(runtime, provider, category, recipeId);
            if (result != null) {
                return result;
            }
        }
        return RecipeNavigationResult.failed("stale_reference", "Recipe reference is stale");
    }

    private static RecipeNavigationResult readiness() {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            return RecipeNavigationResult.failed(
                    "wrong_thread", "Recipe viewer navigation requires the client thread");
        }
        if (TomeWispJeiPlugin.runtime() == null) {
            return RecipeNavigationResult.failed(
                    "viewer_unavailable", "JEI runtime is not available");
        }
        return null;
    }
}
