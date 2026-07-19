package dev.openallay.integration.jei;

import dev.openallay.recipe.RecipeViewerNavigatorRegistry;
import dev.openallay.recipe.RecipeViewerProviderRegistry;
import dev.openallay.client.gui.nativeview.NativeDomainViewProviderRegistry;
import mezz.jei.api.runtime.IJeiRuntime;

/** Loader JEI plugins delegate lifecycle state into this common integration boundary. */
public final class OpenAllayJeiBridge {
    private static volatile IJeiRuntime runtime;

    static {
        RecipeViewerProviderRegistry.register(
                "viewer:jei",
                (capturedAt, platform) -> new JeiRecipeProvider(runtime, capturedAt, platform));
        RecipeViewerNavigatorRegistry.register(new JeiRecipeNavigator());
        NativeDomainViewProviderRegistry.register(new JeiNativeRecipeViewProvider(() -> runtime));
    }

    private OpenAllayJeiBridge() {}

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
