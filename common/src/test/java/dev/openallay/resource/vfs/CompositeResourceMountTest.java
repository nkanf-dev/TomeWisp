package dev.openallay.resource.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

final class CompositeResourceMountTest {
    private static final ResourcePath ROOT = ResourcePath.of("mod");

    @Test
    void atomicallyMergesMetadataResourceWithRawChildren() {
        ResourcePath mod = ROOT.child("example");
        ResourcePath raw = mod.child("raw");
        ResourceMount metadata = mount("metadata", Map.of(
                ROOT, directory(ROOT),
                mod, record(mod, Map.of("version", new ResourceValue.Scalar("1.0")))));
        ResourceMount resources = mount("raw", Map.of(
                ROOT, directory(ROOT),
                mod, directory(mod),
                raw, directory(raw)));

        ResourceSnapshot snapshot = new CompositeResourceMount(ROOT, List.of(metadata, resources)).snapshot();

        assertEquals(ResourceKind.RECORD, snapshot.nodes().get(mod).kind());
        assertEquals(List.of(raw), snapshot.nodes().get(mod).children().stream().map(ResourceEntry::path).toList());
        assertTrue(snapshot.nodes().containsKey(raw));
    }

    @Test
    void rejectsConflictingExactTruthAtOnePath() {
        ResourcePath mod = ROOT.child("example");
        ResourceMount first = mount("first", Map.of(ROOT, directory(ROOT), mod,
                record(mod, Map.of("version", new ResourceValue.Scalar("1")))));
        ResourceMount second = mount("second", Map.of(ROOT, directory(ROOT), mod,
                record(mod, Map.of("version", new ResourceValue.Scalar("2")))));

        CompositeResourceMount composite = new CompositeResourceMount(ROOT, List.of(first, second));
        assertThrows(IllegalArgumentException.class, composite::snapshot);
    }

    private static ResourceMount mount(String id, Map<ResourcePath, ResourceNode> nodes) {
        return new ResourceMount() {
            @Override
            public ResourcePath root() {
                return ROOT;
            }

            @Override
            public ResourceSnapshot snapshot() {
                return new ResourceSnapshot(ROOT, id, Instant.EPOCH, new TreeMap<>(nodes));
            }
        };
    }

    private static ResourceNode directory(ResourcePath path) {
        return new ResourceNode(path, ResourceKind.DIRECTORY, new ResourceValue.DirectoryValue(0),
                List.of(), List.of(), evidence(), ResourcePresentation.none());
    }

    private static ResourceNode record(ResourcePath path, Map<String, ResourceValue> fields) {
        return new ResourceNode(path, ResourceKind.RECORD, new ResourceValue.RecordValue(fields),
                List.of(), List.of(), evidence(), ResourcePresentation.none());
    }

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(DataAuthority.DETERMINISTIC_TEST, DataCompleteness.COMPLETE,
                Instant.EPOCH, "openallay:test", "openallay:test", "26.2", "test", Map.of());
    }
}
