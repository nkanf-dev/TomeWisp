package dev.openallay.resource.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ModResourceSnapshotTest {
    @Test
    void choosesHighestPrecedenceAndRetainsShadowedCandidates() throws Exception {
        ModResourceEntry lower = text("example", "assets/example/lang/en_us.json", 10, "fabric:example/root/1",
                "{\"name\":\"old\"}");
        ModResourceEntry higher = text("example", "assets/example/lang/en_us.json", 20, "fabric:example/root/0",
                "{\"name\":\"new\"}");

        ModResourceSnapshot snapshot = ModResourceSnapshot.available(
                Instant.EPOCH, List.of(lower, higher), Map.of());

        assertEquals(ModResourceSnapshot.Status.AVAILABLE, snapshot.status());
        assertEquals(2, snapshot.entries().size());
        assertEquals(ModResourceEntry.Disposition.ACTIVE, snapshot.entries().getFirst().disposition());
        assertEquals("fabric:example/root/0", snapshot.entries().getFirst().sourceId());
        assertEquals(ModResourceEntry.Disposition.SHADOWED, snapshot.entries().get(1).disposition());
    }

    @Test
    void safelyClassifiesPublicLogicalResources() {
        assertTrue(ModResourceEntry.PublicLocation.parse("assets/example/lang/en_us.json").isPresent());
        assertTrue(ModResourceEntry.PublicLocation.parse("data/example/recipe/cake.json").isPresent());
        assertTrue(ModResourceEntry.PublicLocation.parse("fabric.mod.json").isPresent());
        assertTrue(ModResourceEntry.PublicLocation.parse("META-INF/neoforge.mods.toml").isPresent());

        assertFalse(ModResourceEntry.PublicLocation.parse("dev/example/Entrypoint.class").isPresent());
        assertFalse(ModResourceEntry.PublicLocation.parse("META-INF/EXAMPLE.RSA").isPresent());
        assertFalse(ModResourceEntry.PublicLocation.parse("assets/example/../secret.txt").isPresent());
        assertFalse(ModResourceEntry.PublicLocation.parse("assets/example/native/libexample.dylib").isPresent());
        assertFalse(ModResourceEntry.PublicLocation.parse(".env").isPresent());
    }

    @Test
    void textIsDetachedAndBinaryNeverExposesPayload() throws Exception {
        byte[] source = "hello".getBytes(StandardCharsets.UTF_8);
        ModResourceEntry text = ModResourceEntry.capture(
                "example",
                ModResourceEntry.PublicLocation.parse("assets/example/readme.txt").orElseThrow(),
                new ByteArrayInputStream(source),
                source.length,
                1,
                "fabric:example/root/0");
        source[0] = 'x';
        assertEquals("hello", text.text().orElseThrow());

        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        ModResourceEntry binary = ModResourceEntry.capture(
                "example",
                ModResourceEntry.PublicLocation.parse("assets/example/textures/icon.png").orElseThrow(),
                new ByteArrayInputStream(png),
                png.length,
                1,
                "fabric:example/root/0");
        assertEquals(ModResourceEntry.ContentKind.BINARY_METADATA, binary.contentKind());
        assertTrue(binary.text().isEmpty());
        assertEquals(64, binary.sha256().length());
    }

    @Test
    void rejectsPhysicalOrAmbiguousProvenance() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> text(
                "example", "assets/example/readme.txt", 1, "/Users/player/mod.jar", "hello"));
        assertThrows(IllegalArgumentException.class, () -> text(
                "example", "assets/example/readme.txt", 1, "file:/mods/example.jar", "hello"));
    }

    @Test
    void unavailableSnapshotIsExplicit() {
        ModResourceSnapshot unavailable = ModResourceSnapshot.unavailable(
                Instant.EPOCH, "capture_not_supported");
        assertEquals(ModResourceSnapshot.Status.UNAVAILABLE, unavailable.status());
        assertTrue(unavailable.entries().isEmpty());
        assertEquals("capture_not_supported", unavailable.diagnostics().get("platform"));
    }

    private static ModResourceEntry text(
            String modId, String path, int precedence, String sourceId, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return ModResourceEntry.capture(
                modId,
                ModResourceEntry.PublicLocation.parse(path).orElseThrow(),
                new ByteArrayInputStream(bytes),
                bytes.length,
                precedence,
                sourceId);
    }
}
