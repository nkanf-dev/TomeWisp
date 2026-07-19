package dev.tomewisp.settings.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.capability.CapabilityKind;
import dev.tomewisp.capability.CapabilityPolicy;
import dev.tomewisp.capability.CapabilitySettingsCatalog;
import dev.tomewisp.capability.CapabilitySettingsDescriptor;
import dev.tomewisp.capability.ClientCapabilityResolver;
import dev.tomewisp.capability.ClientCapabilitySnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.integration.patchouli.PatchouliMultiblockStore;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.skill.LoadSkillTool;
import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.skill.SkillSource;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CapabilitySettingsBackendTest {
    @TempDir Path temporary;

    record Input() {}
    record Output(String value) {}

    @Test
    void dependencyConflictDoesNotWriteOrPublish() {
        Fixture fixture = fixture();
        Path path = temporary.resolve("capabilities.json");
        AtomicReference<ClientCapabilitySnapshot> published =
                new AtomicReference<>(fixture.initial());
        CapabilitySettingsBackend backend = new CapabilitySettingsBackend(
                path, fixture.runtime(), fixture.initial(), published::set);

        ToolResult.Failure<CapabilitySettingsView> failure = failure(
                backend.saveCapabilities(new CapabilityPolicy(
                        CapabilityPolicy.SCHEMA_VERSION,
                        Set.of("test:fact"),
                        Set.of())));

        assertEquals("capability_dependency_conflict", failure.code());
        assertFalse(Files.exists(path));
        assertSame(fixture.initial(), published.get());
    }

    @Test
    void successfulSavePublishesAfterPersistenceAndRetainsUnknownDeniedIds() throws Exception {
        Fixture fixture = fixture();
        Path path = temporary.resolve("capabilities.json");
        AtomicReference<ClientCapabilitySnapshot> published =
                new AtomicReference<>(fixture.initial());
        CapabilitySettingsBackend backend = new CapabilitySettingsBackend(
                path,
                fixture.runtime(),
                fixture.initial(),
                snapshot -> {
                    assertTrue(Files.exists(path));
                    published.set(snapshot);
                });
        CapabilityPolicy candidate = new CapabilityPolicy(
                CapabilityPolicy.SCHEMA_VERSION,
                Set.of("test:fact", "future:tool"),
                Set.of("fact-guide", "future-skill"));

        CapabilitySettingsView saved = success(backend.saveCapabilities(candidate));

        assertEquals(candidate, published.get().policy());
        assertEquals(Set.of("future:tool"), saved.unknownDisabledTools());
        assertEquals(Set.of("future-skill"), saved.unknownDisabledSkills());
        assertFalse(saved.catalog().entries().stream()
                .filter(entry -> entry.id().equals("test:fact"))
                .findFirst().orElseThrow().enabled());
        assertTrue(Files.readString(path).contains("future:tool"));
    }

    @Test
    void reconstructedToolPolicyPublishesWithoutWritingLegacyCapabilityFile() {
        Fixture fixture = fixture();
        Path path = temporary.resolve("capabilities.json");
        AtomicReference<ClientCapabilitySnapshot> published =
                new AtomicReference<>(fixture.initial());
        CapabilitySettingsBackend backend = new CapabilitySettingsBackend(
                path, fixture.runtime(), fixture.initial(), published::set);
        CapabilityPolicy candidate = new CapabilityPolicy(
                CapabilityPolicy.SCHEMA_VERSION, Set.of("test:fact"), Set.of());

        CapabilitySettingsView view = success(backend.publishCapabilities(candidate));

        CapabilityPolicy normalized = new CapabilityPolicy(
                CapabilityPolicy.SCHEMA_VERSION,
                Set.of("test:fact"),
                Set.of("fact-guide"));
        assertEquals(normalized, published.get().policy());
        assertEquals(normalized, view.policy());
        assertFalse(Files.exists(path));
    }

    private static Fixture fixture() {
        ToolRegistry tools = new ToolRegistry();
        tools.register("test:provider", List.of(tool()));
        SkillRepository skills = new SkillRepository(new SkillParser(), Set.of("test:fact"));
        assertTrue(skills.reload(List.of(new SkillSource(
                "test-pack",
                "fact-guide/skill.md",
                Map.of("fact-guide/skill.md", """
                        ---
                        name: fact-guide
                        description: Use facts
                        required-mods: []
                        allowed-tools: [test:fact]
                        references: []
                        ---
                        Use the fact.
                        """))), Set.of()));
        tools.register("tomewisp:skills", List.of(new LoadSkillTool(skills)));
        CapabilitySettingsCatalog catalog = new CapabilitySettingsCatalog();
        catalog.register("test:owner", List.of(
                descriptor("test:fact", CapabilityKind.TOOL),
                descriptor("fact-guide", CapabilityKind.SKILL)));
        TomeWispRuntime runtime = new TomeWispRuntime(
                new PlatformService() {
                    @Override public String platformName() { return "test"; }
                    @Override public boolean isModLoaded(String modId) { return false; }
                    @Override public boolean isDevelopmentEnvironment() { return true; }
                },
                tools,
                new KnowledgeRegistry(),
                new PatchouliMultiblockStore(),
                skills,
                new DevelopmentToolInspector(tools),
                null,
                catalog);
        ClientCapabilitySnapshot initial = ((ToolResult.Success<ClientCapabilitySnapshot>)
                new ClientCapabilityResolver().resolve(
                        CapabilityPolicy.defaults(), tools.registrations(), skills)).value();
        return new Fixture(runtime, initial);
    }

    private static CapabilitySettingsDescriptor descriptor(String id, CapabilityKind kind) {
        String key = id.replace(':', '_').replace('-', '_');
        return new CapabilitySettingsDescriptor(
                id,
                kind,
                "settings.test." + key + ".title",
                "settings.test." + key + ".description",
                null);
    }

    private static Tool<Input, Output> tool() {
        return new Tool<>() {
            private final ToolDescriptor<Input, Output> descriptor = new ToolDescriptor<>(
                    "test:fact", "Return a fact", Input.class, Output.class, ToolAccess.READ_ONLY);
            @Override public ToolDescriptor<Input, Output> descriptor() { return descriptor; }
            @Override public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
                return new ToolResult.Success<>(new Output("fact"));
            }
        };
    }

    private record Fixture(TomeWispRuntime runtime, ClientCapabilitySnapshot initial) {}

    @SuppressWarnings("unchecked")
    private static CapabilitySettingsView success(ToolResult<CapabilitySettingsView> result) {
        return ((ToolResult.Success<CapabilitySettingsView>)
                assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<CapabilitySettingsView> failure(
            ToolResult<CapabilitySettingsView> result) {
        return (ToolResult.Failure<CapabilitySettingsView>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
