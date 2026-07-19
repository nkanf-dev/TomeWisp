package dev.tomewisp.neoforge;

import dev.tomewisp.integration.jei.TomeWispJeiBridge;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;

/** NeoForge-root JEI discovery adapter for the common integration. */
@JeiPlugin
public final class TomeWispNeoForgeJeiPlugin implements IModPlugin {
    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath("tomewisp", "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        TomeWispJeiBridge.runtimeAvailable(runtime);
    }

    @Override
    public void onRuntimeUnavailable() {
        TomeWispJeiBridge.runtimeUnavailable();
    }
}
