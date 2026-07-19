package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GuideDisplayConfigLoaderTest {
    @TempDir Path temporary;

    @Test
    void missingConfigDefaultsToDebugOffAndAnimationsOn() {
        GuideDisplayConfigLoader.Load load = new GuideDisplayConfigLoader()
                .load(temporary.resolve("missing.json"));

        assertEquals(GuideDisplayConfig.defaults(), load.config());
        assertFalse(load.config().debugMode());
        assertTrue(load.config().animationsEnabled());
        assertNull(load.failure());
    }

    @Test
    void readsVersionedDebugMode() throws Exception {
        Path path = temporary.resolve("display.json");
        Files.writeString(path,
                "{\"schemaVersion\":2,\"debugMode\":true,\"animationsEnabled\":false}");

        GuideDisplayConfigLoader.Load load = new GuideDisplayConfigLoader().load(path);

        assertTrue(load.config().debugMode());
        assertFalse(load.config().animationsEnabled());
        assertNull(load.failure());
    }

    @Test
    void canonicalWriterRoundTripsThroughStrictReaderLoader() {
        GuideDisplayConfig candidate = new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION, true, false);

        String encoded = new GuideDisplayConfigWriter().encode(candidate);
        GuideDisplayConfigLoader.Load load = new GuideDisplayConfigLoader()
                .load(new StringReader(encoded));

        assertEquals(candidate, load.config());
        assertNull(load.failure());
        assertEquals("""
                {
                  \"schemaVersion\": 2,
                  \"debugMode\": true,
                  \"animationsEnabled\": false
                }
                """, encoded);
    }

    @Test
    void rejectsUnknownFieldsFutureVersionsAndWrongTypesWithoutRewriting() throws Exception {
        GuideDisplayConfigLoader loader = new GuideDisplayConfigLoader();
        Path path = temporary.resolve("display.json");
        for (String invalid : new String[] {
                "{\"schemaVersion\":2,\"debugMode\":false,\"animationsEnabled\":true,\"rawSecrets\":true}",
                "{\"schemaVersion\":1,\"debugMode\":false}",
                "{\"schemaVersion\":3,\"debugMode\":false,\"animationsEnabled\":true}",
                "{\"schemaVersion\":2,\"debugMode\":\"yes\",\"animationsEnabled\":true}"
        }) {
            Files.writeString(path, invalid);

            GuideDisplayConfigLoader.Load load = loader.load(path);

            assertEquals(GuideDisplayConfig.defaults(), load.config());
            assertNotNull(load.failure());
            assertEquals("invalid_display_config", load.failure().code());
            assertEquals(invalid, Files.readString(path));
        }
    }
}
