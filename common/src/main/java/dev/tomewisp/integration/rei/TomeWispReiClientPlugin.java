package dev.tomewisp.integration.rei;

import dev.tomewisp.recipe.RecipeViewerNavigatorRegistry;
import dev.tomewisp.recipe.RecipeViewerProviderRegistry;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;

public class TomeWispReiClientPlugin implements REIClientPlugin {
    static {
        RecipeViewerProviderRegistry.register(
                "viewer:rei",
                (capturedAt, platform) -> new ReiRecipeProvider(capturedAt, platform));
        RecipeViewerNavigatorRegistry.register(new ReiRecipeNavigator());
    }
}
