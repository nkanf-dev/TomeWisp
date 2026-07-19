package dev.tomewisp.integration.jei;

import dev.tomewisp.recipe.RecipeViewerNavigatorRegistry;
import dev.tomewisp.recipe.RecipeViewerProviderRegistry;
import dev.tomewisp.client.gui.nativeview.NativeDomainViewProviderRegistry;
import mezz.jei.api.runtime.IJeiRuntime;

/** Loader JEI plugins delegate lifecycle state into this common integration boundary. */
public final class TomeWispJeiBridge {
    private static volatile IJeiRuntime runtime;

    static {
        RecipeViewerProviderRegistry.register(
                "viewer:jei",
                (capturedAt, platform) -> new JeiRecipeProvider(runtime, capturedAt, platform));
        RecipeViewerNavigatorRegistry.register(new JeiRecipeNavigator());
        NativeDomainViewProviderRegistry.register(new JeiNativeRecipeViewProvider(() -> runtime));
    }

    private TomeWispJeiBridge() {}

    public static void runtimeAvailable(IJeiRuntime value) {
        runtime = java.util.Objects.requireNonNull(value, "value");
    }

    public static void runtimeUnavailable() {
        runtime = null;
    }

    static IJeiRuntime runtime() {
        return runtime;
    }
}
