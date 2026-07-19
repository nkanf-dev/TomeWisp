package dev.openallay.tool.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.knowledge.KnowledgeLoad;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class LocalMarkdownKnowledgeProviderTest {
    @TempDir Path temporary;

    @Test
    void loadsOnlyDirectMarkdownFilesWithResourceEvidence() throws Exception {
        Path root = temporary.resolve("knowledge");
        Path source = Files.createDirectories(root.resolve("notes"));
        Files.writeString(source.resolve("iron.md"), "# Iron Guide\nUse a furnace.\n");
        Files.writeString(source.resolve("ignored.txt"), "not knowledge");
        Path nested = Files.createDirectories(source.resolve("nested"));
        Files.writeString(nested.resolve("hidden.md"), "# Hidden");
        LocalMarkdownKnowledgeProvider provider = new LocalMarkdownKnowledgeProvider(
                definition("notes"), root, "26.2", "fabric",
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC));

        KnowledgeLoad loaded = provider.load();

        assertEquals(1, loaded.documents().size());
        assertEquals("Iron Guide", loaded.documents().getFirst().title());
        assertEquals("en_us", loaded.documents().getFirst().namespace());
        assertEquals(DataAuthority.RESOURCE_ASSET, loaded.documents().getFirst().evidence().authority());
        assertEquals(DataCompleteness.COMPLETE, loaded.documents().getFirst().evidence().completeness());
        assertEquals(Instant.parse("2026-07-19T00:00:00Z"), loaded.documents().getFirst().evidence().capturedAt());
        assertEquals(1, loaded.evidence().size());
    }

    @Test
    void configRejectsAbsoluteTraversalAndNestedDirectories() {
        assertThrows(
                ToolConfigException.class,
                () -> LocalMarkdownKnowledgeProvider.validateConfig(config("../outside")));
        assertThrows(
                ToolConfigException.class,
                () -> LocalMarkdownKnowledgeProvider.validateConfig(config("/tmp/outside")));
        assertThrows(
                ToolConfigException.class,
                () -> LocalMarkdownKnowledgeProvider.validateConfig(config("nested/notes")));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void rejectsSymlinkedSourceDirectoriesAndMarkdownFiles() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("knowledge"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.writeString(outside.resolve("outside.md"), "# Outside");
        Files.createSymbolicLink(root.resolve("notes"), outside);
        LocalMarkdownKnowledgeProvider linkedDirectory = new LocalMarkdownKnowledgeProvider(
                definition("notes"), root, "26.2", "fabric");
        assertThrows(IllegalArgumentException.class, linkedDirectory::load);

        Files.delete(root.resolve("notes"));
        Path source = Files.createDirectories(root.resolve("notes"));
        Files.createSymbolicLink(source.resolve("linked.md"), outside.resolve("outside.md"));
        LocalMarkdownKnowledgeProvider linkedFile = new LocalMarkdownKnowledgeProvider(
                definition("notes"), root, "26.2", "fabric");
        assertThrows(IllegalArgumentException.class, linkedFile::load);
    }

    private static ToolSourceDefinition definition(String directory) {
        return new ToolSourceDefinition(
                "user:notes", "local_markdown", "Notes", true, config(directory),
                ToolSourceDefinition.Lifecycle.USER);
    }

    private static JsonObject config(String directory) {
        JsonObject config = new JsonObject();
        config.addProperty("directory", directory);
        config.addProperty("locale", "en_us");
        return config;
    }
}
