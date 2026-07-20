package dev.openallay.resource.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.DataAuthority;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.skill.SkillCatalog;
import dev.openallay.skill.SkillDocument;
import dev.openallay.skill.SkillMetadata;
import dev.openallay.skill.SkillSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SkillResourceMountTest {
    @Test
    void exposesValidatedInstructionsAndReferencesAsProceduralDocuments() {
        SkillDocument skill = new SkillDocument(
                new SkillMetadata(
                        "compare-game-data",
                        "Compare typed game records.",
                        Optional.of("MIT"),
                        Optional.empty(),
                        Map.of("category", "analysis"),
                        Set.of(),
                        Set.of("openallay:resource_query"),
                        List.of("references/examples.md"),
                        "openallay:bundled_skill",
                        SkillSource.Origin.BUNDLED),
                "Use resource_query after reading @schema.",
                Map.of("references/examples.md", "Example pipeline."));
        SkillCatalog catalog = new SkillCatalog() {
            @Override public Optional<SkillDocument> find(String name) {
                return skill.metadata().name().equals(name) ? Optional.of(skill) : Optional.empty();
            }

            @Override public List<SkillMetadata> metadata() {
                return List.of(skill.metadata());
            }
        };
        Instant capturedAt = Instant.parse("2026-07-20T00:00:00Z");
        SkillResourceMount mount = new SkillResourceMount(
                () -> catalog, "26.2", "fabric", Clock.fixed(capturedAt, ZoneOffset.UTC));

        var snapshot = mount.snapshot();
        ResourcePath metadataPath = ResourcePath.parse("/skill/compare-game-data");
        ResourcePath instructionsPath = ResourcePath.parse("/skill/compare-game-data/instructions");
        ResourcePath referencePath = ResourcePath.parse(
                "/skill/compare-game-data/reference/references%2Fexamples.md");

        assertEquals(ResourceKind.RECORD, snapshot.nodes().get(metadataPath).kind());
        assertEquals(ResourceKind.DOCUMENT, snapshot.nodes().get(instructionsPath).kind());
        assertEquals(ResourceKind.DOCUMENT, snapshot.nodes().get(referencePath).kind());
        assertEquals(DataAuthority.RESOURCE_ASSET,
                snapshot.nodes().get(referencePath).evidence().authority());
        assertEquals("procedural_only",
                snapshot.nodes().get(referencePath).evidence().details().get("openallay:authority"));
        ResourceValue.RecordValue metadata =
                (ResourceValue.RecordValue) snapshot.nodes().get(metadataPath).truth();
        assertTrue(metadata.fields().get("instructions") instanceof ResourceValue.ReferenceValue);
        assertEquals(List.of(instructionsPath, referencePath),
                snapshot.nodes().get(metadataPath).links().stream().map(link -> link.target()).toList());
    }
}
