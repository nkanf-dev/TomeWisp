package dev.openallay.resource.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourceValue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ModRawMountTest {
    @Test
    void exposesCanonicalActivePathsAndSourceCandidates() throws Exception {
        ModResourceEntry old = entry("assets/example/lang/en_us.json", 1, "fabric:example/root/1", "old");
        ModResourceEntry active = entry("assets/example/lang/en_us.json", 2, "fabric:example/root/0", "new");
        ModResourceEntry data = entry("data/example/recipe/cake.json", 1, "fabric:example/root/0", "recipe");
        ModResourceEntry metadata = entry("fabric.mod.json", 1, "fabric:example/root/0", "metadata");
        ModRawMount mount = new ModRawMount(
                () -> ModResourceSnapshot.available(Instant.EPOCH, List.of(old, active, data, metadata), Map.of()),
                ModRawMountTest::evidence);

        var snapshot = mount.snapshot();
        ResourcePath asset = ResourcePath.parse("/mod/example/raw/assets/example/lang%2Fen_us.json");
        ResourcePath source = asset.child("@source").child("fabric:example/root/0");
        assertTrue(snapshot.nodes().containsKey(asset));
        assertTrue(snapshot.nodes().containsKey(source));
        assertTrue(snapshot.nodes().containsKey(
                ResourcePath.parse("/mod/example/raw/data/example/recipe%2Fcake.json")));
        assertTrue(snapshot.nodes().containsKey(
                ResourcePath.parse("/mod/example/raw/metadata/fabric.mod.json")));
        assertEquals("true", snapshot.nodes().get(asset).evidence().details().get("openallay:active"));
        assertEquals("1", snapshot.nodes().get(asset).evidence().details().get("openallay:shadowed_count"));
        assertEquals(ResourceKind.DOCUMENT, snapshot.nodes().get(asset).kind());
    }

    @Test
    void binaryIsMetadataOnlyAndUsesLogicalPresentationReference() throws Exception {
        byte[] payload = {(byte) 0x89, 'P', 'N', 'G'};
        ModResourceEntry binary = ModResourceEntry.capture(
                "example",
                ModResourceEntry.PublicLocation.parse("assets/example/textures/icon.png").orElseThrow(),
                new ByteArrayInputStream(payload),
                payload.length,
                1,
                "fabric:example/root/0");
        ModRawMount mount = new ModRawMount(
                () -> ModResourceSnapshot.available(Instant.EPOCH, List.of(binary), Map.of()),
                ModRawMountTest::evidence);

        var node = mount.snapshot().nodes().get(
                ResourcePath.parse("/mod/example/raw/assets/example/textures%2Ficon.png"));
        assertEquals(ResourceKind.BINARY_METADATA, node.kind());
        assertInstanceOf(ResourceValue.BinaryMetadataValue.class, node.truth());
        assertEquals("/mod/example/raw/assets/example/textures%2Ficon.png",
                node.presentation().references().get("resource"));
    }

    @Test
    void unavailableCapturePublishesExplicitFailureWithoutFabricatingContent() {
        ModRawMount mount = new ModRawMount(
                () -> ModResourceSnapshot.unavailable(Instant.EPOCH, "public_enumeration_unavailable"),
                ModRawMountTest::evidence);

        var snapshot = mount.snapshot();
        var status = snapshot.nodes().get(ResourcePath.parse("/mod/@raw-status"));
        assertEquals(ResourceKind.FAILURE, status.kind());
        ResourceValue.FailureValue failure = assertInstanceOf(ResourceValue.FailureValue.class, status.truth());
        assertEquals("resource_capture_unavailable", failure.code());
    }

    @Test
    void malformedTextDegradesOnlyThatResource() throws Exception {
        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        ModResourceEntry malformed = ModResourceEntry.capture(
                "example",
                ModResourceEntry.PublicLocation.parse("assets/example/lang/en_us.json").orElseThrow(),
                new ByteArrayInputStream(invalidUtf8),
                invalidUtf8.length,
                1,
                "fabric:example/root/0");
        ModRawMount mount = new ModRawMount(
                () -> ModResourceSnapshot.available(Instant.EPOCH, List.of(malformed), Map.of()),
                ModRawMountTest::evidence);

        var node = mount.snapshot().nodes().get(
                ResourcePath.parse("/mod/example/raw/assets/example/lang%2Fen_us.json"));
        assertEquals(ResourceKind.FAILURE, node.kind());
        assertEquals("resource_content_unavailable",
                assertInstanceOf(ResourceValue.FailureValue.class, node.truth()).code());
    }

    private static ModResourceEntry entry(String path, int precedence, String source, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return ModResourceEntry.capture(
                "example",
                ModResourceEntry.PublicLocation.parse(path).orElseThrow(),
                new ByteArrayInputStream(bytes),
                bytes.length,
                precedence,
                source);
    }

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(
                DataAuthority.DETERMINISTIC_TEST,
                DataCompleteness.COMPLETE,
                Instant.EPOCH,
                "openallay:test",
                "openallay:test",
                "26.2",
                "test",
                Map.of());
    }
}
