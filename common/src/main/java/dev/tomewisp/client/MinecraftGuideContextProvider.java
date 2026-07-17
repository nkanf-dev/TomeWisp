package dev.tomewisp.client;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.client.context.ClientContextCapture;
import dev.tomewisp.client.resource.MinecraftClientResourceAccess;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.guide.GuideContextProvider;
import dev.tomewisp.integration.ftb.quests.FtbQuestsKnowledgeProvider;
import dev.tomewisp.integration.ftb.quests.ReflectiveFtbQuestsBridge;
import dev.tomewisp.integration.patchouli.PatchouliKnowledgeProvider;
import dev.tomewisp.knowledge.KnowledgeSourceProvider;
import dev.tomewisp.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;

public final class MinecraftGuideContextProvider implements GuideContextProvider {
    private final TomeWispRuntime runtime;
    private final Minecraft client;
    private final Gson gson;
    private final ClassLoader integrationLoader;

    public MinecraftGuideContextProvider(
            TomeWispRuntime runtime,
            Minecraft client,
            Gson gson,
            ClassLoader integrationLoader) {
        this.runtime = runtime;
        this.client = client;
        this.gson = gson;
        this.integrationLoader = integrationLoader;
    }

    @Override
    public ToolResult<ToolInvocationContext> capture(
            Set<ContextCapability> capabilities, String correlationId) {
        if (client.player == null) {
            return new ToolResult.Failure<>(
                    "player_required", "No client player is connected");
        }
        try {
            ToolResult<Integer> refreshed = refreshKnowledge();
            if (refreshed instanceof ToolResult.Failure<Integer> failure) {
                return new ToolResult.Failure<>(failure.code(), failure.message());
            }
            return new ToolResult.Success<>(new ClientContextCapture(gson, runtime.platform())
                    .capture(client, capabilities, correlationId));
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "context_capture_failed",
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage());
        }
    }

    @Override
    public ToolResult<Integer> refreshKnowledge() {
        try {
            List<KnowledgeSourceProvider> providers = new ArrayList<>();
            providers.add(new PatchouliKnowledgeProvider(
                    new MinecraftClientResourceAccess(client.getResourceManager()),
                    client.getLanguageManager().getSelected(),
                    runtime.patchouliMultiblocks(),
                    runtime.platform().gameVersion(),
                    runtime.platform().platformName()));
            if (runtime.platform().isModLoaded("ftbquests") && client.player != null) {
                providers.add(new FtbQuestsKnowledgeProvider(
                        new ReflectiveFtbQuestsBridge(integrationLoader),
                        client.player,
                        true,
                        runtime.platform().gameVersion(),
                        runtime.platform().platformName()));
            }
            if (!runtime.knowledge().reload(providers)) {
                return new ToolResult.Failure<>(
                        "knowledge_refresh_failed", runtime.knowledge().diagnostics().toString());
            }
            return new ToolResult.Success<>(runtime.knowledge().snapshot().documents().size());
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "knowledge_refresh_failed",
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage());
        }
    }
}
