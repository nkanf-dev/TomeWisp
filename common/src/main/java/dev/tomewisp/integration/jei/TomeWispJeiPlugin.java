package dev.tomewisp.integration.jei;

import dev.tomewisp.recipe.RecipeViewerProviderRegistry;
import dev.tomewisp.recipe.RecipeViewerNavigatorRegistry;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;

@JeiPlugin
public final class TomeWispJeiPlugin implements IModPlugin {
    private static volatile IJeiRuntime runtime;

    static {
        RecipeViewerProviderRegistry.register(
                "viewer:jei",
                (capturedAt, platform) -> new JeiRecipeProvider(runtime, capturedAt, platform));
        RecipeViewerNavigatorRegistry.register(new JeiRecipeNavigator());
    }

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath("tomewisp", "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime value) {
        runtime = value;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    static IJeiRuntime runtime() {
        return runtime;
    }
}
