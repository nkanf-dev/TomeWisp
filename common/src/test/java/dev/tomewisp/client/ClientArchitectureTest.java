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
        assertEquals(4, delivered.size());
    }

    @Test
    void bothLoadersUseTheSharedDisplayConfigWithoutLeakingLoaderApisIntoCommon() throws Exception {
        Path root = repositoryRoot();
        List<Path> entrypoints = List.of(
                root.resolve("fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java"),
                root.resolve("neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java"));
        for (Path entrypoint : entrypoints) {
            String source = Files.readString(entrypoint);
            assertTrue(source.contains("GuideDisplayConfigLoader"), entrypoint::toString);
            assertTrue(source.contains("tomewisp/display.json"), entrypoint::toString);
            assertTrue(source.contains("display.config()"), entrypoint::toString);
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

    private static final class FakePlatform implements PlatformService {
        @Override public String platformName() { return "test"; }
        @Override public boolean isModLoaded(String modId) { return false; }
        @Override public boolean isDevelopmentEnvironment() { return true; }
    }
}
