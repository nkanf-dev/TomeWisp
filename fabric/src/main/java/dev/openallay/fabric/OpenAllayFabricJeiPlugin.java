package dev.openallay.fabric;

import dev.openallay.integration.jei.OpenAllayJeiBridge;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;

/** Fabric-root JEI discovery adapter for the common integration. */
@JeiPlugin
public final class OpenAllayFabricJeiPlugin implements IModPlugin {
    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath("openallay", "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        OpenAllayJeiBridge.runtimeAvailable(runtime);
    }

    @Override
    public void onRuntimeUnavailable() {
        OpenAllayJeiBridge.runtimeUnavailable();
    }
}
