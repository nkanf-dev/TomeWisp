package dev.openallay.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.openallay.context.RegistryEntrySnapshot;
import dev.openallay.context.RegistrySnapshot;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.minecraft.RegistryCatalogCapture;
import dev.openallay.testing.GroundedTestFixtures;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.query.QueryOperation;
import dev.openallay.tool.query.RegistryQueryEngine;
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
                Map.of("openallay:state", "not_loaded"));
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

    @Test
    void ranksFoodByCapturedSaturationInOnePipeline() {
        RegistrySnapshot base = catalog();
        RegistrySnapshot foods = new RegistrySnapshot(base.evidence(), List.of(
                entry("farmersdelight:apple_pie", "item", "Apple Pie", List.of(), Set.of(),
                        Set.of("minecraft:food"), Map.of("nutrition", "8", "saturation", "9.6")),
                entry("farmersdelight:honey_glazed_ham", "item", "Honey Glazed Ham", List.of(), Set.of(),
                        Set.of("minecraft:food"), Map.of("nutrition", "12", "saturation", "15.0")),
                entry("minecraft:bread", "item", "Bread", List.of(), Set.of(),
                        Set.of("minecraft:food"), Map.of("nutrition", "5", "saturation", "6.0"))));
        List<QueryOperation> pipeline = List.of(
                new QueryOperation(QueryOperation.Op.FILTER, "/namespace", QueryOperation.Operator.EQ,
                        "farmersdelight", null, null, null, null, null),
                new QueryOperation(QueryOperation.Op.FILTER, "/data/test:saturation", QueryOperation.Operator.EXISTS,
                        null, null, null, null, null, null),
                new QueryOperation(QueryOperation.Op.SORT, "/data/test:saturation", null, null,
                        null, QueryOperation.Direction.DESC, null, null, null),
                new QueryOperation(QueryOperation.Op.SELECT, null, null, null,
                        List.of("/id", "/displayName", "/data/test:nutrition", "/data/test:saturation"),
                        null, null, null, null),
                new QueryOperation(QueryOperation.Op.TAKE, null, null, null,
                        null, null, null, null, 1));

        ToolResult.Success<ResolveResourceTool.Output> result = assertInstanceOf(
                ToolResult.Success.class,
                tool.invoke(context(foods), new ResolveResourceTool.Input(
                        null, ResolveResourceTool.Kind.item, RegistryQueryEngine.Dataset.items, pipeline)));

        assertEquals(1, result.value().analysis().rows().size());
        assertEquals("farmersdelight:honey_glazed_ham",
                result.value().analysis().rows().getFirst().get("/id").getAsString());
        assertEquals("15.0", result.value().analysis().rows().getFirst()
                .get("/data/test:saturation").getAsString());
        assertEquals(5, result.value().analysis().stages().size());
    }

    @Test
    void discoversAndExpandsArbitraryTypedModPropertiesWithoutFieldHardCoding() {
        JsonArray effects = new JsonArray();
        JsonObject shortEffect = new JsonObject();
        shortEffect.addProperty("id", "example:slow");
        shortEffect.addProperty("duration", 120);
        effects.add(shortEffect);
        JsonObject longEffect = new JsonObject();
        longEffect.addProperty("id", "example:haste");
        longEffect.addProperty("duration", 900);
        effects.add(longEffect);
        JsonObject payload = new JsonObject();
        payload.add("effects", effects);
        RegistrySnapshot source = new RegistrySnapshot(catalog().evidence(), List.of(
                entryProperties("example:tonic", "item", "Tonic", Map.of("example:payload", payload))));

        ToolResult.Success<ResolveResourceTool.Output> described = assertInstanceOf(
                ToolResult.Success.class,
                tool.invoke(context(source), new ResolveResourceTool.Input(
                        null, ResolveResourceTool.Kind.item, RegistryQueryEngine.Dataset.items,
                        "example", true, null)));
        assertTrue(described.value().schema().fields().stream().anyMatch(field ->
                field.path().equals("/data/example:payload/effects/*/duration")
                        && field.types().contains("number")));

        List<QueryOperation> pipeline = List.of(
                new QueryOperation(QueryOperation.Op.EXPAND,
                        "/data/example:payload/effects", null, null, null, null, null, null, null),
                new QueryOperation(QueryOperation.Op.SORT,
                        "/data/example:payload/effects/duration", null, null, null,
                        QueryOperation.Direction.DESC, null, null, null),
                new QueryOperation(QueryOperation.Op.SELECT, null, null, null,
                        List.of("/id", "/data/example:payload/effects/id",
                                "/data/example:payload/effects/duration"),
                        null, null, null, null),
                new QueryOperation(QueryOperation.Op.TAKE, null, null, null,
                        null, null, null, null, 1));
        ToolResult.Success<ResolveResourceTool.Output> queried = assertInstanceOf(
                ToolResult.Success.class,
                tool.invoke(context(source), new ResolveResourceTool.Input(
                        null, ResolveResourceTool.Kind.item, RegistryQueryEngine.Dataset.items,
                        "example", null, pipeline)));
        assertEquals(900, queried.value().analysis().rows().getFirst()
                .get("/data/example:payload/effects/duration").getAsInt());
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
        Map<String, JsonElement> properties = new java.util.TreeMap<>();
        metadata.forEach((key, value) -> properties.put("test:" + key, new JsonPrimitive(value)));
        return entryProperties(id, kind, display, properties, aliases, tags, components);
    }

    private RegistryEntrySnapshot entryProperties(
            String id,
            String kind,
            String display,
            Map<String, JsonElement> properties) {
        return entryProperties(id, kind, display, properties, List.of(), Set.of(), Set.of());
    }

    private RegistryEntrySnapshot entryProperties(
            String id,
            String kind,
            String display,
            Map<String, JsonElement> properties,
            List<String> aliases,
            Set<String> tags,
            Set<String> components) {
        return new RegistryEntrySnapshot(
                id,
                kind,
                display,
                id.substring(0, id.indexOf(':')),
                "minecraft:test_registry",
                aliases,
                tags,
                components,
                properties);
    }
}
