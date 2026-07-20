package dev.openallay.resource.mount;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.resource.vfs.ResourcePath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InstalledModResourceMountTest {
    @Test
    void exposesExactInstalledModMetadata() {
        InstalledModMetadata mod = new InstalledModMetadata("farmersdelight", "Farmer's Delight", "1.0", "Food",
                List.of("Author"), List.of("MIT"), Map.of(), "client_server", List.of("minecraft"));
        InstalledModResourceMount mount = new InstalledModResourceMount(() -> List.of(mod), () -> evidence());
        assertTrue(mount.snapshot().nodes().containsKey(ResourcePath.parse("/mod/farmersdelight")));
    }

    private static EvidenceMetadata evidence() {
        return new EvidenceMetadata(DataAuthority.DETERMINISTIC_TEST, DataCompleteness.COMPLETE,
                Instant.EPOCH, "openallay:test", "openallay:test", "26.2", "test", Map.of());
    }
}
