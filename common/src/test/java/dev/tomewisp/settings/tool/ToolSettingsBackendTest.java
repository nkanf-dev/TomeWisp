package dev.tomewisp.settings.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.tomewisp.capability.CapabilityCatalogSnapshot;
import dev.tomewisp.capability.CapabilityKind;
import dev.tomewisp.capability.CapabilityPolicy;
import dev.tomewisp.capability.CapabilitySettingsEntry;
import dev.tomewisp.recipe.RecipeVisibilityPolicy;
import dev.tomewisp.recipe.config.RecipeClientConfig;
import dev.tomewisp.settings.capability.CapabilitySettingsView;
import dev.tomewisp.settings.capability.RecipeSettingsView;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.tool.RegisteredTool;
import dev.tomewisp.tool.builtin.ResolveResourceTool;
import dev.tomewisp.tool.config.ToolConfigException;
import dev.tomewisp.tool.config.ToolFamilyConfig;
import dev.tomewisp.tool.config.ToolFamilyId;
import dev.tomewisp.tool.config.ToolFamilySettingsStore;
import dev.tomewisp.tool.config.ToolSourceDefinition;
import dev.tomewisp.tool.config.ToolSourceKind;
import dev.tomewisp.tool.config.ToolSourceKindRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolSettingsBackendTest {
    @TempDir Path temporary;

    @Test
    void projectsSixFriendlyFamiliesWithSourcesAsToolChildren() {
        Fixture fixture = fixture(CapabilityPolicy.defaults());

        ToolSettingsView view = fixture.backend().currentView();

        assertEquals(List.of(ToolFamilyId.values()), view.families().stream()
                .map(ToolSettingsView.Family::id)
                .toList());
        ToolSettingsView.Family recipes = view.find(ToolFamilyId.RECIPES).orElseThrow();
        assertEquals("tomewisp.settings.tools.recipes.title", recipes.titleKey());
        assertEquals(1, recipes.sources().size());
        assertFalse(recipes.sources().getFirst().editable());
        assertFalse(recipes.sources().getFirst().deletable());
        ToolSettingsView.RecipeDetail detail = recipes.recipes().orElseThrow();
        assertEquals("UNLOCKED_ONLY", detail.visibility());
        assertEquals("viewer:rei", detail.preferredViewer());
        assertTrue(detail.preferredViewerAvailable());
        assertEquals("viewer:rei", detail.discoveredSources().getFirst().id());
        assertTrue(view.find(ToolFamilyId.GUIDES).orElseThrow().recipes().isEmpty());
        ToolSettingsView.Member resolver = view.find(ToolFamilyId.RESOURCE_RESOLUTION)
                .orElseThrow().members().getFirst();
        assertEquals("test:provider", resolver.providerId());
        assertEquals("tomewisp__resolve_resource", resolver.modelAlias());
        assertEquals("string", resolver.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("query").get("type").getAsString());
        assertTrue(resolver.outputSchema().has("properties"));
    }

    @Test
    void toolEnablementPersistsCompleteFamilyAndProducesCompleteCallablePolicy() throws Exception {
        CapabilityPolicy initial = new CapabilityPolicy(
                CapabilityPolicy.SCHEMA_VERSION,
                Set.of("future:tool"),
                Set.of("guide-skill"));
        Fixture fixture = fixture(initial);

        ToolSettingsBackend.State disabled = success(
                fixture.backend().setToolEnabled(ToolFamilyId.RECIPES, false));

        assertTrue(disabled.capabilityPolicy().disabledTools()
                .containsAll(ToolFamilyId.RECIPES.memberToolIds()));
        assertTrue(disabled.capabilityPolicy().disabledTools().contains("future:tool"));
        assertEquals(Set.of("guide-skill"), disabled.capabilityPolicy().disabledSkills());
        assertFalse(disabled.view().find(ToolFamilyId.RECIPES).orElseThrow().enabled());
        assertTrue(Files.readString(temporary.resolve("tools/recipes.json"))
                .contains("\"enabled\": false"));

        fixture.capabilities().set(capabilities(disabled.capabilityPolicy()));
        ToolSettingsBackend.State enabled = success(
                fixture.backend().setToolEnabled(ToolFamilyId.RECIPES, true));
        assertTrue(ToolFamilyId.RECIPES.memberToolIds().stream()
                .noneMatch(enabled.capabilityPolicy().disabledTools()::contains));
        assertTrue(enabled.capabilityPolicy().disabledTools().contains("future:tool"));
        assertTrue(enabled.view().find(ToolFamilyId.RECIPES).orElseThrow().enabled());
    }

    @Test
    void sourceEnablementAndUserCrudRetainLifecycleBoundaries() throws Exception {
        CapabilityPolicy existingPolicy = new CapabilityPolicy(
                CapabilityPolicy.SCHEMA_VERSION,
                Set.of("tomewisp:search_knowledge", "future:tool"),
                Set.of("guide-skill"));
        Fixture fixture = fixture(existingPolicy);
        ToolSettingsBackend backend = fixture.backend();

        ToolSettingsBackend.State disabled = success(backend.setSourceEnabled(
                ToolFamilyId.GUIDES, "tomewisp:patchouli", false));
        ToolSettingsView.Source patchouli = disabled.view().find(ToolFamilyId.GUIDES)
                .orElseThrow().sources().getFirst();
        assertFalse(patchouli.enabled());
        assertFalse(patchouli.editable());
        assertFalse(patchouli.deletable());
        assertEquals(existingPolicy, disabled.capabilityPolicy());

        ToolSourceDefinition notes = local("user:notes", "Notes", "notes");
        ToolSettingsBackend.State created = success(backend.createSource(ToolFamilyId.GUIDES, notes));
        ToolSettingsView.Source createdView = created.view().find(ToolFamilyId.GUIDES)
                .orElseThrow().sources().get(1);
        assertTrue(createdView.editable());
        assertTrue(createdView.deletable());

        ToolSourceDefinition edited = local("user:notes", "Edited Notes", "edited-notes");
        assertEquals("Edited Notes", success(backend.updateSource(
                        ToolFamilyId.GUIDES, "user:notes", edited))
                .view().find(ToolFamilyId.GUIDES).orElseThrow().sources().get(1).displayName());
        assertEquals(1, success(backend.deleteSource(ToolFamilyId.GUIDES, "user:notes"))
                .view().find(ToolFamilyId.GUIDES).orElseThrow().sources().size());
    }

    @Test
    void restoreDefaultsRemovesUserSourcesAndReenablesBuiltIns() {
        Fixture fixture = fixture(CapabilityPolicy.defaults());
        ToolSettingsBackend backend = fixture.backend();
        success(backend.setSourceEnabled(ToolFamilyId.GUIDES, "tomewisp:patchouli", false));
        success(backend.createSource(
                ToolFamilyId.GUIDES, local("user:notes", "Notes", "notes")));

        ToolSettingsBackend.State restored =
                success(backend.restoreDefaults(ToolFamilyId.GUIDES));

        ToolSettingsView.Family guides =
                restored.view().find(ToolFamilyId.GUIDES).orElseThrow();
        assertTrue(guides.enabled());
        assertEquals(1, guides.sources().size());
        assertTrue(guides.sources().getFirst().enabled());
        assertFalse(guides.sources().getFirst().editable());
    }

    @Test
    void invalidCandidateRetainsPriorFileAndPublishedFamily() throws Exception {
        Fixture fixture = fixture(CapabilityPolicy.defaults());
        ToolSettingsBackend backend = fixture.backend();
        success(backend.setSourceEnabled(ToolFamilyId.GUIDES, "tomewisp:patchouli", false));
        Path file = temporary.resolve("tools/guides.json");
        String priorBytes = Files.readString(file);
        ToolFamilyConfig prior = backend.current(ToolFamilyId.GUIDES);

        ToolResult.Failure<ToolSettingsBackend.State> deleteFailure = failure(
                backend.deleteSource(ToolFamilyId.GUIDES, "tomewisp:patchouli"));
        assertEquals("builtin_source_required", deleteFailure.code());
        assertEquals(priorBytes, Files.readString(file));
        assertEquals(prior, backend.current(ToolFamilyId.GUIDES));

        ToolSourceDefinition wrongOwner = local("user:wrong-owner", "Wrong", "wrong");
        ToolResult.Failure<ToolSettingsBackend.State> ownerFailure = failure(
                backend.createSource(ToolFamilyId.RECIPES, wrongOwner));
        assertEquals("source_owner_mismatch", ownerFailure.code());
        assertEquals(priorBytes, Files.readString(file));
        assertEquals(prior, backend.current(ToolFamilyId.GUIDES));
    }

    @Test
    void viewAndKindMetadataAreDefensivelyImmutable() {
        Fixture fixture = fixture(CapabilityPolicy.defaults());
        ToolSettingsView.Source source = fixture.backend().currentView()
                .find(ToolFamilyId.GUIDES).orElseThrow().sources().getFirst();

        source.config().addProperty("outside", true);

        assertFalse(fixture.backend().currentView().find(ToolFamilyId.GUIDES)
                .orElseThrow().sources().getFirst().config().has("outside"));
        assertEquals(List.of("local_markdown"), fixture.backend()
                .creatableSourceKinds(ToolFamilyId.GUIDES).stream()
                .map(ToolSettingsBackend.ToolSourceKindView::kind)
                .toList());
        assertTrue(fixture.backend().creatableSourceKinds(ToolFamilyId.RECIPES).isEmpty());
    }

    private Fixture fixture(CapabilityPolicy policy) {
        ToolSourceKindRegistry registry = registry();
        EnumMap<ToolFamilyId, ToolFamilySettingsStore> stores = new EnumMap<>(ToolFamilyId.class);
        for (ToolFamilyId id : ToolFamilyId.values()) {
            stores.put(id, new ToolFamilySettingsStore(
                    temporary.resolve("tools"), id, registry, defaults(id)));
        }
        AtomicReference<CapabilitySettingsView> capabilities =
                new AtomicReference<>(capabilities(policy));
        RecipeSettingsView recipes = new RecipeSettingsView(
                new RecipeClientConfig(
                        RecipeClientConfig.SCHEMA_VERSION,
                        RecipeVisibilityPolicy.UNLOCKED_ONLY,
                        "viewer:rei",
                        Set.of("future:viewer")),
                List.of(new RecipeSettingsView.Source("viewer:rei", true, true, true, true)),
                Set.of("future:viewer"),
                true);
        return new Fixture(
                new ToolSettingsBackend(
                        stores,
                        registry,
                        capabilities::get,
                        () -> recipes,
                        List.of(new RegisteredTool("test:provider", new ResolveResourceTool()))),
                capabilities);
    }

    private static ToolSourceKindRegistry registry() {
        return ToolSourceKindRegistry.builder()
                .register(emptyKind("recipe_catalog", ToolFamilyId.RECIPES))
                .register(emptyKind("patchouli", ToolFamilyId.GUIDES))
                .register(ToolSourceKind.localMarkdown())
                .build();
    }

    private static ToolSourceKind emptyKind(String kind, ToolFamilyId owner) {
        return new ToolSourceKind(
                kind,
                owner,
                Set.of(ToolSourceDefinition.Lifecycle.BUILT_IN),
                false,
                List.of(),
                config -> {
                    if (!config.keySet().isEmpty()) {
                        throw new ToolConfigException(
                                "invalid_source_config", "Built-in config must be empty");
                    }
                    return config;
                });
    }

    private static ToolFamilyConfig defaults(ToolFamilyId id) {
        if (id == ToolFamilyId.RECIPES) {
            return new ToolFamilyConfig(1, id, true, List.of(new ToolSourceDefinition(
                    "minecraft:client_recipe_book",
                    "recipe_catalog",
                    "Minecraft Recipes",
                    true,
                    new JsonObject(),
                    ToolSourceDefinition.Lifecycle.BUILT_IN)));
        }
        if (id == ToolFamilyId.GUIDES) {
            return new ToolFamilyConfig(1, id, true, List.of(new ToolSourceDefinition(
                    "tomewisp:patchouli",
                    "patchouli",
                    "Patchouli Guides",
                    true,
                    new JsonObject(),
                    ToolSourceDefinition.Lifecycle.BUILT_IN)));
        }
        return ToolFamilyConfig.empty(id);
    }

    private static CapabilitySettingsView capabilities(CapabilityPolicy policy) {
        List<CapabilitySettingsEntry> entries = java.util.Arrays.stream(ToolFamilyId.values())
                .flatMap(family -> family.memberToolIds().stream())
                .map(id -> new CapabilitySettingsEntry(
                        "test:owner",
                        id,
                        CapabilityKind.TOOL,
                        "settings.test.title",
                        "settings.test.description",
                        null,
                        true,
                        !policy.disabledTools().contains(id)))
                .toList();
        return new CapabilitySettingsView(
                policy,
                new CapabilityCatalogSnapshot(entries),
                policy.disabledTools().stream()
                        .filter(id -> ToolFamilyId.forCallableTool(id).isEmpty())
                        .collect(java.util.stream.Collectors.toSet()),
                policy.disabledSkills());
    }

    private static ToolSourceDefinition local(String id, String displayName, String directory) {
        JsonObject config = new JsonObject();
        config.addProperty("directory", directory);
        config.addProperty("locale", "en_us");
        return new ToolSourceDefinition(
                id,
                "local_markdown",
                displayName,
                true,
                config,
                ToolSourceDefinition.Lifecycle.USER);
    }

    private record Fixture(
            ToolSettingsBackend backend,
            AtomicReference<CapabilitySettingsView> capabilities) {}

    @SuppressWarnings("unchecked")
    private static ToolSettingsBackend.State success(
            ToolResult<ToolSettingsBackend.State> result) {
        return ((ToolResult.Success<ToolSettingsBackend.State>)
                assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<ToolSettingsBackend.State> failure(
            ToolResult<ToolSettingsBackend.State> result) {
        return (ToolResult.Failure<ToolSettingsBackend.State>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
