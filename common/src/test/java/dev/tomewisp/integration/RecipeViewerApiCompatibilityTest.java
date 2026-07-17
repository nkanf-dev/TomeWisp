package dev.tomewisp.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class RecipeViewerApiCompatibilityTest {
    @Test
    void jeiPublicApiExposesEnumerationSlotsLifecycleAndNavigation() throws IOException {
        assertSymbols("mezz.jei.api.IModPlugin", "onRuntimeAvailable", "onRuntimeUnavailable");
        assertSymbols("mezz.jei.api.runtime.IJeiRuntime", "getRecipeManager", "getRecipesGui");
        assertSymbols("mezz.jei.api.recipe.IRecipeManager", "createRecipeCategoryLookup");
        assertSymbols("mezz.jei.api.recipe.IRecipeCategoriesLookup", "includeHidden", "get");
        assertSymbols("mezz.jei.api.gui.IRecipeLayoutDrawable", "getRecipeSlotsView");
        assertSymbols("mezz.jei.api.gui.ingredient.IRecipeSlotDrawablesView", "getSlots");
        assertSymbols("mezz.jei.api.runtime.IRecipesGui", "show");
    }

    @Test
    void reiPublicApiExposesDisplaysGroupedEntriesAndNavigation() throws IOException {
        assertSymbols(
                "me.shedaniel.rei.api.client.registry.display.DisplayRegistry", "getInstance");
        assertSymbols(
                "me.shedaniel.rei.api.common.registry.display.DisplayRegistryCommon", "getAll");
        assertSymbols(
                "me.shedaniel.rei.api.common.display.Display",
                "getInputEntries",
                "getOutputEntries",
                "getDisplayLocation");
        assertSymbols("me.shedaniel.rei.api.common.entry.EntryStack", "getIdentifier");
        assertSymbols(
                "me.shedaniel.rei.api.client.view.ViewSearchBuilder",
                "builder",
                "addRecipesFor",
                "addUsagesFor",
                "open");
    }

    private void assertSymbols(String className, String... symbols) throws IOException {
        String resource = className.replace('.', '/') + ".class";
        byte[] classfile;
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertTrue(input != null, () -> "Missing API class " + className);
            classfile = input.readAllBytes();
        }
        String constantPool = new String(classfile, StandardCharsets.ISO_8859_1);
        for (String symbol : symbols) {
            assertTrue(
                    constantPool.contains(symbol),
                    () -> className + " no longer exposes symbol " + symbol);
        }
    }
}
