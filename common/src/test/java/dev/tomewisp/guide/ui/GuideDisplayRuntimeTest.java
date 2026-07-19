package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.settings.SettingsWriteException;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GuideDisplayRuntimeTest {
    @TempDir Path temporary;

    @Test
    void successfulSavePublishesCanonicalConfigAndReloadsExternalChanges() throws Exception {
        Path path = temporary.resolve("display.json");
        GuideDisplayRuntime runtime = new GuideDisplayRuntime(path);

        ToolResult<GuideDisplayConfig> saved = runtime.save(new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION, true, false));

        assertTrue(saved instanceof ToolResult.Success<GuideDisplayConfig>);
        assertTrue(runtime.config().debugMode());
        assertNull(runtime.failure());
        assertTrue(Files.readString(path).contains("\"debugMode\": true"));

        Files.writeString(path,
                "{\"schemaVersion\":2,\"debugMode\":false,\"animationsEnabled\":true}");
        ToolResult<GuideDisplayConfig> reloaded = runtime.reload();

        assertTrue(reloaded instanceof ToolResult.Success<GuideDisplayConfig>);
        assertFalse(runtime.config().debugMode());
        assertNull(runtime.failure());
    }

    @Test
    void invalidReloadRetainsLastValidProjectionAndReportsFailure() throws Exception {
        Path path = temporary.resolve("display.json");
        GuideDisplayRuntime runtime = new GuideDisplayRuntime(path);
        runtime.save(new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION, true, false));
        Files.writeString(path,
                "{\"schemaVersion\":1,\"debugMode\":false}");

        ToolResult<GuideDisplayConfig> result = runtime.reload();

        ToolResult.Failure<GuideDisplayConfig> failure =
                (ToolResult.Failure<GuideDisplayConfig>) result;
        assertEquals("invalid_display_config", failure.code());
        assertTrue(runtime.config().debugMode());
        assertNotNull(runtime.failure());
        assertEquals("invalid_display_config", runtime.failure().code());
    }

    @Test
    void failedDebugSaveRetainsFileAndProjection() throws Exception {
        Path path = temporary.resolve("display.json");
        String original =
                "{\"schemaVersion\":2,\"debugMode\":false,\"animationsEnabled\":true}";
        Files.writeString(path, original);
        GuideDisplayRuntime runtime = new GuideDisplayRuntime(
                path,
                (target, contents) -> {
                    throw new SettingsWriteException();
                });

        ToolResult<GuideDisplayConfig> result =
                runtime.save(new GuideDisplayConfig(
                        GuideDisplayConfig.SCHEMA_VERSION, true, false));

        ToolResult.Failure<GuideDisplayConfig> failure =
                (ToolResult.Failure<GuideDisplayConfig>) result;
        assertEquals("settings_write_failed", failure.code());
        assertFalse(runtime.config().debugMode());
        assertEquals(original, Files.readString(path));
        assertEquals("settings_write_failed", runtime.failure().code());
    }
}
