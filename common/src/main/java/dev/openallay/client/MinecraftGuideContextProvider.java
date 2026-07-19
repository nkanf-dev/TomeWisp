package dev.openallay.client;

import com.google.gson.Gson;
import dev.openallay.OpenAllayRuntime;
import dev.openallay.client.context.ClientContextCapture;
import dev.openallay.client.resource.MinecraftClientResourceAccess;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.guide.GuideContextProvider;
import dev.openallay.integration.ftb.quests.FtbQuestsKnowledgeProvider;
import dev.openallay.integration.ftb.quests.ReflectiveFtbQuestsBridge;
import dev.openallay.integration.patchouli.PatchouliKnowledgeProvider;
import dev.openallay.knowledge.KnowledgeSourceProvider;
import dev.openallay.recipe.config.RecipeClientRuntime;
import dev.openallay.recipe.RecipeProviderReadiness;
import dev.openallay.recipe.RecipeProviderReadinessGate;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;

public final class MinecraftGuideContextProvider implements GuideContextProvider {
    private final OpenAllayRuntime runtime;
    private final Minecraft client;
    private final Gson gson;
    private final ClassLoader integrationLoader;
    private final RecipeClientRuntime recipeClient;
    private final RecipeProviderReadinessGate recipeReadiness = new RecipeProviderReadinessGate();

    public MinecraftGuideContextProvider(
            OpenAllayRuntime runtime,
            Minecraft client,
            Gson gson,
            ClassLoader integrationLoader) {
        this(runtime, client, gson, integrationLoader, RecipeClientRuntime.defaults());
    }

    public MinecraftGuideContextProvider(
            OpenAllayRuntime runtime,
            Minecraft client,
            Gson gson,
            ClassLoader integrationLoader,
            RecipeClientRuntime recipeClient) {
        this.runtime = runtime;
        this.client = client;
        this.gson = gson;
        this.integrationLoader = integrationLoader;
        this.recipeClient = java.util.Objects.requireNonNull(recipeClient, "recipeClient");
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
            return new ToolResult.Success<>(new ClientContextCapture(gson, runtime.platform(), recipeClient)
                    .capture(client, capabilities, correlationId));
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "context_capture_failed",
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage());
        }
    }

    public RecipeProviderReadiness recipeProviderReadiness() {
        try {
            return new ClientContextCapture(gson, runtime.platform(), recipeClient)
                    .recipeProviderReadiness(client, recipeReadiness);
        } catch (RuntimeException failure) {
            return RecipeProviderReadiness.failed(
                    "recipe_readiness_failed", "Recipe provider readiness check failed");
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
