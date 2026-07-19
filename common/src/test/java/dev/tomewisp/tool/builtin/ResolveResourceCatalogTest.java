package dev.tomewisp.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.context.RegistryEntrySnapshot;
import dev.tomewisp.context.RegistrySnapshot;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.context.minecraft.RegistryCatalogCapture;
import dev.tomewisp.testing.GroundedTestFixtures;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ResolveResourceCatalogTest {
    private final ResolveResourceTool tool = new ResolveResourceTool();

    @Test
    void searchesAcrossItemsEffectsAndPotionsWithoutTruncation() {
        var result = success("poison", null);

        assertEquals(
                List.of(
                        "potion:minecraft:poison",
                        "effect:minecraft:poison",
                        "item:minecraft:poisonous_potato"),
                result.value().matches().stream()
                        .map(match -> match.kind() + ":" + match.id())
                        .toList());
        assertEquals("exact_name", result.value().matches().getFirst().matchQuality());
        assertTrue(result.value().matches().get(1).matchedFields().contains("path"));
    }

    @Test
    void supportsLocalizedTextAndKindFiltering() {
        var result = success("中毒", ResolveResourceTool.Kind.effect);

        assertEquals(1, result.value().matches().size());
        assertEquals("minecraft:poison", result.value().matches().getFirst().id());
        assertEquals("exact_name", result.value().matches().getFirst().matchQuality());
    }

    @Test
    void bookItemsAreSearchableButUnrelatedGuideTextIsNotInvented() {
        var byLocalizedName = success("书", ResolveResourceTool.Kind.item);
        var byPoison = success("poison", ResolveResourceTool.Kind.item);

        assertEquals("minecraft:book", byLocalizedName.value().matches().getFirst().id());
        assertTrue(byPoison.value().matches().stream()
                .noneMatch(match -> match.id().equals("minecraft:book")));
    }

    @Test
    void searchesTagsComponentsAndPublicMetadata() {
        var byTag = success("#minecraft:food", ResolveResourceTool.Kind.item);
        var byComponent = success("minecraft:consumable", ResolveResourceTool.Kind.item);
        var byMetadata = success("beneficial false", ResolveResourceTool.Kind.effect);

        assertEquals("minecraft:poisonous_potato", byTag.value().matches().getFirst().id());
        assertTrue(byTag.value().matches().getFirst().matchedFields().contains("tags"));
        assertEquals("minecraft:poisonous_potato", byComponent.value().matches().getFirst().id());
        assertTrue(byComponent.value().matches().getFirst().matchedFields().contains("components"));
        assertEquals("minecraft:poison", byMetadata.value().matches().getFirst().id());
        assertTrue(byMetadata.value().matches().getFirst().matchedFields().contains("metadata"));
    }

    @Test
    void typoSearchIsDeterministicAndExplicitlyMarkedFuzzy() {
        var result = success("poision", ResolveResourceTool.Kind.effect);

        assertEquals("minecraft:poison", result.value().matches().getFirst().id());
        assertEquals("fuzzy", result.value().matches().getFirst().matchQuality());
    }

    @Test
    void unknownSearchIsAnEvidencedEmptySuccess() {
        var result = success("definitely absent catalog content", null);

        assertFalse(result.value().exists());
        assertTrue(result.value().matches().isEmpty());
        assertEquals(catalog().evidence(), result.value().evidence().getFirst());
    }

    @Test
    void incompleteCatalogKeepsUnknownAuthorityOnAnEmptyResult() {
        EvidenceMetadata base = catalog().evidence();
        EvidenceMetadata unknown = new EvidenceMetadata(
                base.authority(),
                DataCompleteness.UNKNOWN,
                base.capturedAt(),
                base.sourceId(),
                base.provenance(),
                base.gameVersion(),
                base.loader(),
                Map.of("tomewisp:state", "not_loaded"));
        RegistrySnapshot unavailable = new RegistrySnapshot(unknown, List.of());

        ToolResult.Success<ResolveResourceTool.Output> result = assertInstanceOf(
                ToolResult.Success.class,
                tool.invoke(context(unavailable), new ResolveResourceTool.Input("poison", null)));

        assertFalse(result.value().exists());
        assertEquals(DataCompleteness.UNKNOWN, result.value().evidence().getFirst().completeness());
    }

    @Test
    void snapshotDoesNotExposeWorldContainerOrPathData() {
        Set<String> components = java.util.Arrays.stream(RegistryEntrySnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(components.contains("position"));
        assertFalse(components.contains("inventory"));
        assertFalse(components.contains("container"));
        assertFalse(components.contains("path"));
    }

    @Test
    void catalogCaptureFailsBeforeTouchingRegistriesOffOwningThread() {
        assertThrows(
                IllegalStateException.class,
                () -> RegistryCatalogCapture.capture("minecraft:test", () -> false));
    }

    private ToolResult.Success<ResolveResourceTool.Output> success(
            String query, ResolveResourceTool.Kind kind) {
        return assertInstanceOf(
                ToolResult.Success.class,
                tool.invoke(context(), new ResolveResourceTool.Input(query, kind)));
    }

    private ToolInvocationContext context() {
        return context(catalog());
    }

    private ToolInvocationContext context(RegistrySnapshot registries) {
        ToolInvocationContext base = GroundedTestFixtures.fullContext();
        return new ToolInvocationContext(
                base.correlationId(),
                base.capturedAt(),
                base.caller(),
                base.player(),
                Optional.of(registries),
                base.recipes(),
                base.observableGameState(),
                base.metrics());
    }

    private RegistrySnapshot catalog() {
        RegistrySnapshot base = GroundedTestFixtures.registries();
        return new RegistrySnapshot(base.evidence(), List.of(
                entry(
                        "minecraft:poison",
                        "effect",
                        "中毒",
                        List.of("effect.minecraft.poison"),
                        Set.of("minecraft:harmful"),
                        Set.of(),
                        Map.of("beneficial", "false", "category", "harmful")),
                entry(
                        "minecraft:poison",
                        "potion",
                        "Poison",
                        List.of("poison"),
                        Set.of(),
                        Set.of(),
                        Map.of("effects", "minecraft:poison")),
                entry(
                        "minecraft:poisonous_potato",
                        "item",
                        "Poisonous Potato",
                        List.of("item.minecraft.poisonous_potato"),
                        Set.of("minecraft:food"),
                        Set.of("minecraft:consumable"),
                        Map.of("max_stack_size", "64")),
                entry(
                        "minecraft:book",
                        "item",
                        "书",
                        List.of("item.minecraft.book"),
                        Set.of("minecraft:bookshelf_books"),
                        Set.of(),
                        Map.of("max_stack_size", "64"))));
    }

    private RegistryEntrySnapshot entry(
            String id,
            String kind,
            String display,
            List<String> aliases,
            Set<String> tags,
            Set<String> components,
            Map<String, String> metadata) {
        return new RegistryEntrySnapshot(
                id,
                kind,
                display,
                "minecraft",
                "minecraft:test_registry",
                aliases,
                tags,
                components,
                metadata);
    }
}
