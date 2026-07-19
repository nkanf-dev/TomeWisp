package dev.openallay.integration.rei;

import dev.openallay.recipe.RecipeViewerNavigatorRegistry;
import dev.openallay.recipe.RecipeViewerProviderRegistry;
import dev.openallay.client.gui.nativeview.NativeDomainViewProviderRegistry;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;

public class OpenAllayReiClientPlugin implements REIClientPlugin {
    static {
        RecipeViewerProviderRegistry.register(
                "viewer:rei",
                (capturedAt, platform) -> new ReiRecipeProvider(capturedAt, platform));
        RecipeViewerNavigatorRegistry.register(new ReiRecipeNavigator());
        NativeDomainViewProviderRegistry.register(new ReiNativeRecipeViewProvider());
    }
}
