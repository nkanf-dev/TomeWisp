package dev.tomewisp.tool.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.tomewisp.settings.SettingsWriteException;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolFamilySettingsStoreTest {
    @TempDir Path temporary;

    @Test
    void builtInSourcesCannotBeDeletedOrHaveTheirIdentityChanged() {
        ToolFamilySettingsStore store = store(defaults(), (path, contents) -> Files.writeString(path, contents));

        ToolFamilyConfig deleted = new ToolFamilyConfig(1, ToolFamilyId.GUIDES, true, List.of());
        assertEquals("builtin_source_required", failure(store.save(deleted)).code());

        ToolSourceDefinition renamed = new ToolSourceDefinition(
                "tomewisp:patchouli", "patchouli", "Renamed", true, new JsonObject(),
                ToolSourceDefinition.Lifecycle.BUILT_IN);
        ToolFamilyConfig changed = new ToolFamilyConfig(1, ToolFamilyId.GUIDES, true, List.of(renamed));
        assertEquals("builtin_identity_immutable", failure(store.save(changed)).code());
    }

    @Test
    void userSourcesSupportCreateEditAndDeleteAsCompleteCandidates() throws Exception {
        ToolFamilySettingsStore store = store(defaults(), (path, contents) -> {
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents);
        });
        ToolSourceDefinition user = local("user:notes", "notes", "Notes");

        ToolFamilyConfig created = success(store.save(new ToolFamilyConfig(
                1, ToolFamilyId.GUIDES, true, List.of(defaults().sources().getFirst(), user)))).value();
        assertEquals(2, created.sources().size());
        assertTrue(Files.exists(temporary.resolve("tools/guides.json")));

        ToolSourceDefinition edited = local("user:notes", "notes", "Edited Notes");
        assertEquals("Edited Notes", success(store.save(new ToolFamilyConfig(
                1, ToolFamilyId.GUIDES, true, List.of(defaults().sources().getFirst(), edited))))
                .value().sources().get(1).displayName());

        assertEquals(1, success(store.save(defaults())).value().sources().size());
    }

    @Test
    void failedReplacementRetainsPriorFileAndPublishedCandidate() throws Exception {
        Path target = temporary.resolve("tools/guides.json");
        ToolFamilySettingsStore initial = store(defaults(), (path, contents) -> {
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents);
        });
        success(initial.save(defaults()));
        String priorBytes = Files.readString(target);

        ToolFamilySettingsStore failing = store(defaults(), (path, contents) -> {
            throw new SettingsWriteException();
        });
        success(failing.load());
        ToolFamilyConfig candidate = new ToolFamilyConfig(1, ToolFamilyId.GUIDES, false, defaults().sources());

        assertEquals("settings_write_failed", failure(failing.save(candidate)).code());
        assertEquals(priorBytes, Files.readString(target));
        assertEquals(defaults(), failing.current());
    }

    @Test
    void userCannotCreateARegisteredBuiltInOnlyKind() {
        ToolFamilySettingsStore store = store(defaults(), (path, contents) -> {});
        ToolSourceDefinition forged = new ToolSourceDefinition(
                "user:patchouli", "patchouli", "Forged", true, new JsonObject(),
                ToolSourceDefinition.Lifecycle.USER);

        ToolResult.Failure<ToolFamilyConfig> failure = failure(store.save(new ToolFamilyConfig(
                1, ToolFamilyId.GUIDES, true, List.of(defaults().sources().getFirst(), forged))));
        assertEquals("source_kind_not_user_creatable", failure.code());
    }

    private ToolFamilySettingsStore store(
            ToolFamilyConfig defaults, ToolFamilySettingsStore.FileReplacement replacement) {
        ToolSourceKindRegistry registry = ToolSourceKindRegistry.builder()
                .register(new ToolSourceKind(
                        "patchouli", ToolFamilyId.GUIDES,
                        java.util.Set.of(ToolSourceDefinition.Lifecycle.BUILT_IN), false,
                        List.of(), config -> {
                            if (!config.keySet().isEmpty()) {
                                throw new ToolConfigException(
                                        "invalid_source_config",
                                        "patchouli config must be empty");
                            }
                            return config;
                        }))
                .register(ToolSourceKind.localMarkdown())
                .build();
        return new ToolFamilySettingsStore(
                temporary.resolve("tools"), ToolFamilyId.GUIDES, registry, defaults, replacement);
    }

    private static ToolFamilyConfig defaults() {
        return new ToolFamilyConfig(1, ToolFamilyId.GUIDES, true, List.of(new ToolSourceDefinition(
                "tomewisp:patchouli", "patchouli", "Patchouli", true, new JsonObject(),
                ToolSourceDefinition.Lifecycle.BUILT_IN)));
    }

    private static ToolSourceDefinition local(String id, String directory, String displayName) {
        JsonObject config = new JsonObject();
        config.addProperty("directory", directory);
        config.addProperty("locale", "en_us");
        return new ToolSourceDefinition(
                id, "local_markdown", displayName, true, config,
                ToolSourceDefinition.Lifecycle.USER);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<ToolFamilyConfig> success(ToolResult<ToolFamilyConfig> result) {
        return (ToolResult.Success<ToolFamilyConfig>) assertInstanceOf(ToolResult.Success.class, result);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<ToolFamilyConfig> failure(ToolResult<ToolFamilyConfig> result) {
        return (ToolResult.Failure<ToolFamilyConfig>) assertInstanceOf(ToolResult.Failure.class, result);
    }
}
