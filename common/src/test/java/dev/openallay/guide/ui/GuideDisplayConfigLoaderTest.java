package dev.openallay.guide.ui;

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
    void missingConfigDefaultsToProductNameDebugOffAndAnimationsOn() {
        GuideDisplayConfigLoader.Load load = new GuideDisplayConfigLoader()
                .load(temporary.resolve("missing.json"));

        assertEquals(GuideDisplayConfig.defaults(), load.config());
        assertFalse(load.config().debugMode());
        assertTrue(load.config().animationsEnabled());
        assertEquals("OpenAllay", load.config().assistantName());
        assertNull(load.failure());
    }

    @Test
    void readsVersionedDebugMode() throws Exception {
        Path path = temporary.resolve("display.json");
        Files.writeString(path, """
                {"schemaVersion":3,"debugMode":true,"animationsEnabled":false,
                 "assistantName":"小羽"}
                """);

        GuideDisplayConfigLoader.Load load = new GuideDisplayConfigLoader().load(path);

        assertTrue(load.config().debugMode());
        assertFalse(load.config().animationsEnabled());
        assertEquals("小羽", load.config().assistantName());
        assertNull(load.failure());
    }

    @Test
    void canonicalWriterRoundTripsThroughStrictReaderLoader() {
        GuideDisplayConfig candidate = new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION, true, false, "  小羽  ");

        String encoded = new GuideDisplayConfigWriter().encode(candidate);
        GuideDisplayConfigLoader.Load load = new GuideDisplayConfigLoader()
                .load(new StringReader(encoded));

        assertEquals(candidate, load.config());
        assertNull(load.failure());
        assertEquals("""
                {
                  \"schemaVersion\": 3,
                  \"debugMode\": true,
                  \"animationsEnabled\": false,
                  \"assistantName\": \"小羽\"
                }
                """, encoded);
    }

    @Test
    void rejectsUnknownFieldsFutureVersionsAndWrongTypesWithoutRewriting() throws Exception {
        GuideDisplayConfigLoader loader = new GuideDisplayConfigLoader();
        Path path = temporary.resolve("display.json");
        for (String invalid : new String[] {
                "{\"schemaVersion\":3,\"debugMode\":false,\"animationsEnabled\":true,\"assistantName\":\"OpenAllay\",\"rawSecrets\":true}",
                "{\"schemaVersion\":2,\"debugMode\":false,\"animationsEnabled\":true}",
                "{\"schemaVersion\":4,\"debugMode\":false,\"animationsEnabled\":true,\"assistantName\":\"OpenAllay\"}",
                "{\"schemaVersion\":3,\"debugMode\":\"yes\",\"animationsEnabled\":true,\"assistantName\":\"OpenAllay\"}",
                "{\"schemaVersion\":3,\"debugMode\":false,\"animationsEnabled\":true,\"assistantName\":false}",
                "{\"schemaVersion\":3,\"debugMode\":false,\"animationsEnabled\":true,\"assistantName\":\"   \"}",
                "{\"schemaVersion\":3,\"debugMode\":false,\"animationsEnabled\":true,\"assistantName\":\"bad\\nname\"}"
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
