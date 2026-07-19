package dev.tomewisp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ClientArchitectureTest {
    @Test
    void modelCallbacksArePublishedThroughClientDispatcher() {
        List<Runnable> queued = new ArrayList<>();
        ToolRegistry tools = new ToolRegistry();
        TomeWispRuntime base = new TomeWispRuntime(
                new FakePlatform(),
                tools,
                new KnowledgeRegistry(),
                new dev.tomewisp.integration.patchouli.PatchouliMultiblockStore(),
                new SkillRepository(new SkillParser(), List.of()),
                new DevelopmentToolInspector(tools),
                null);
        ClientGuideRuntime runtime = new ClientGuideRuntime(
                base,
                (request, events, cancellation) -> java.util.concurrent.CompletableFuture.completedFuture(
                        new ModelTurn("test", "test", List.of(new ModelContent.Text("answer")),
                                "end_turn", ModelUsage.empty())),
                new AgentSessionStore(),
                new Gson(),
                queued::add);
        List<AgentEvent> delivered = new ArrayList<>();

        runtime.ask(UUID.randomUUID(), "question",
                dev.tomewisp.context.ToolInvocationContext.developmentConsole("test"), delivered::add).join();
        assertEquals(0, delivered.size());
        queued.forEach(Runnable::run);
        assertEquals(5, delivered.size());
        assertTrue(delivered.stream().anyMatch(event -> event instanceof AgentEvent.ModelProgress));
    }

    @Test
    void bothLoadersShareSettingsHistoryAndDisplayRuntimesWithoutLoaderLeaks()
            throws Exception {
        Path root = repositoryRoot();
        List<Path> entrypoints = List.of(
                root.resolve("fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java"),
                root.resolve("neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java"));
        for (Path entrypoint : entrypoints) {
            String source = Files.readString(entrypoint);
            assertTrue(source.contains("GuideDisplayRuntime"), entrypoint::toString);
            assertEquals(1, occurrences(source, "new GuideDisplayRuntime("),
                    entrypoint::toString);
            assertTrue(source.contains("configDirectory.resolve(\"display.json\")"), entrypoint::toString);
            assertTrue(source.contains("ClientSettingsHistoryBinding"), entrypoint::toString);
            assertEquals(1, occurrences(source, "historySettings.bind(services)"),
                    entrypoint::toString);
            assertTrue(source.contains("ClientModelRuntimeRegistry"), entrypoint::toString);
            assertTrue(source.contains("models.json"), entrypoint::toString);
            assertTrue(source.contains("model-metadata.json"), entrypoint::toString);
            assertTrue(source.contains("configDirectory.resolve(\"capabilities.json\")"),
                    entrypoint::toString);
            assertTrue(source.contains("configDirectory.resolve(\"recipes.json\")"),
                    entrypoint::toString);
            assertEquals(1, occurrences(source, "new RecipeClientRuntime("),
                    entrypoint::toString);
            assertTrue(source.contains("recipeClient,\n                System.getenv()"),
                    entrypoint::toString);
            assertTrue(source.contains("ClientSettingsRuntime"), entrypoint::toString);
            assertTrue(source.contains("TomeWispSettingsScreen"), entrypoint::toString);
            assertTrue(source.contains("service,\n                                recipeClient,\n                                display,"),
                    entrypoint::toString);
            assertTrue(source.contains("services.shutdown()"), entrypoint::toString);
            assertTrue(source.contains("history.closeAsync()"), entrypoint::toString);
            assertTrue(source.contains("settings.closeAsync()"), entrypoint::toString);
            assertTrue(source.indexOf("services.shutdown()")
                    < source.indexOf("history.closeAsync()"), entrypoint::toString);
            assertTrue(source.indexOf("history.closeAsync()")
                    < source.indexOf("settings.closeAsync()"), entrypoint::toString);
        }

        String neoForgeClient = Files.readString(entrypoints.get(1));
        assertTrue(neoForgeClient.contains("ClientStartedEvent"));
        assertTrue(neoForgeClient.contains("start(runtime, bridge, event.getClient())"));
        assertTrue(neoForgeClient.indexOf("new MinecraftGuideHistoryScope(client)")
                > neoForgeClient.indexOf("ClientStartedEvent"));

        List<Path> commands = List.of(
                root.resolve("fabric/src/main/java/dev/tomewisp/fabric/FabricGuideCommands.java"),
                root.resolve("neoforge/src/main/java/dev/tomewisp/neoforge/NeoForgeGuideCommands.java"));
        for (Path command : commands) {
            String source = Files.readString(command);
            assertTrue(source.contains("literal(\"profile\")"), command::toString);
            assertTrue(source.contains("guide.modelProfile("), command::toString);
        }

        String protocol = Files.readString(root.resolve(
                "common/src/main/java/dev/tomewisp/bridge/protocol/BridgeProtocol.java"));
        assertTrue(protocol.contains("VERSION = 6"));
        for (Path bridge : List.of(
                root.resolve("fabric/src/main/java/dev/tomewisp/fabric/network/FabricServerBridge.java"),
                root.resolve("neoforge/src/main/java/dev/tomewisp/neoforge/network/NeoForgeServerBridge.java"))) {
            String source = Files.readString(bridge);
            assertTrue(source.contains("spec.budget().contextWindowTokens()"), bridge::toString);
            assertTrue(source.contains("spec.budget().maxOutputTokens()"), bridge::toString);
            assertTrue(source.contains("spec.canonicalModelId()"), bridge::toString);
        }

        try (var files = Files.walk(root.resolve("common/src/main/java"))) {
            List<Path> violations = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            String source = Files.readString(path);
                            return source.contains("import net.fabricmc.")
                                    || source.contains("import net.neoforged.");
                        } catch (java.io.IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    })
                    .toList();
            assertTrue(violations.isEmpty(), () -> "loader imports in common: " + violations);
        }
    }

    @Test
    void optionalRecipeViewerBridgesRegisterStableSourceAndNavigatorDescriptors()
            throws Exception {
        Path root = repositoryRoot();
        assertViewerBridge(
                root.resolve("common/src/main/java/dev/tomewisp/integration/jei/"
                        + "TomeWispJeiBridge.java"),
                "viewer:jei");
        assertViewerBridge(
                root.resolve("common/src/main/java/dev/tomewisp/integration/rei/"
                        + "TomeWispReiClientPlugin.java"),
                "viewer:rei");
    }

    private static void assertViewerBridge(Path path, String sourceId) throws Exception {
        String source = Files.readString(path);
        assertTrue(source.contains("RecipeViewerProviderRegistry.register("), path::toString);
        assertTrue(source.contains("\"" + sourceId + "\""), path::toString);
        assertTrue(source.contains("RecipeViewerNavigatorRegistry.register("), path::toString);
        assertTrue(source.contains("NativeDomainViewProviderRegistry.register("), path::toString);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("common"))
                && Files.isDirectory(current.resolve("fabric"))) {
            return current;
        }
        if (current.getFileName() != null && current.getFileName().toString().equals("common")) {
            return current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root from " + current);
    }

    private static int occurrences(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static final class FakePlatform implements PlatformService {
        @Override public String platformName() { return "test"; }
        @Override public boolean isModLoaded(String modId) { return false; }
        @Override public boolean isDevelopmentEnvironment() { return true; }
    }
}
