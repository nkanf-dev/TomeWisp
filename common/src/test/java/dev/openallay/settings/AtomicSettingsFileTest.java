package dev.openallay.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AtomicSettingsFileTest {
    @TempDir Path temporary;

    @Test
    void replacesTargetThroughProductionAtomicMove() throws Exception {
        Path target = temporary.resolve("nested/models.json");

        new AtomicSettingsFile().replace(target, "new settings\n");

        assertEquals("new settings\n", Files.readString(target));
        assertEquals(List.of("models.json"), Files.list(target.getParent())
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList());
    }

    @Test
    void moveFailurePreservesOriginalAndCleansTemporarySibling() throws Exception {
        Path target = temporary.resolve("models.json");
        Files.writeString(target, "old settings\n");
        AtomicSettingsFile file = new AtomicSettingsFile((source, destination) -> {
            assertTrue(Files.exists(source));
            assertEquals(target, destination);
            throw new IOException("simulated move failure");
        });

        SettingsWriteException failure = assertThrows(
                SettingsWriteException.class,
                () -> file.replace(target, "new settings\n"));

        assertEquals("settings_write_failed", failure.code());
        assertEquals("Unable to save settings", failure.getMessage());
        assertEquals("old settings\n", Files.readString(target));
        assertEquals(List.of("models.json"), Files.list(temporary)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList());
        assertFalse(failure.toString().contains("simulated move failure"));
    }
}
