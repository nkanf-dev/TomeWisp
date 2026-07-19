package dev.openallay.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SkillSettingsStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsCompleteOverrideAtomicallyThenEditsAndDeletesIt() throws IOException {
        Path root = temporaryDirectory.resolve("skills");
        SkillSource bundled = new SkillSource(
                "openallay:bundled",
                "guide/SKILL.md",
                Map.of(
                        "guide/SKILL.md", skill("guide", "bundled"),
                        "guide/references/policy.md", "Ground claims."),
                SkillSource.Origin.BUNDLED);
        SkillSettingsStore store = new SkillSettingsStore(root, new SkillParser());

        store.createOverride(bundled);
        assertEquals("Ground claims.", Files.readString(root.resolve("guide/references/policy.md")));
        assertEquals("bundled", new SkillParser().parse(new FilesystemSkillLoader()
                .load(root).sources().getFirst()).instructions());

        store.editOverride("guide", skill("guide", "edited"));
        assertEquals("edited", new SkillParser().parse(new FilesystemSkillLoader()
                .load(root).sources().getFirst()).instructions());

        store.deleteOverride("guide");
        assertFalse(Files.exists(root.resolve("guide")));
    }

    @Test
    void rejectsInvalidEditsAndNeverWritesOutsideTheConfiguredRoot() throws IOException {
        Path root = temporaryDirectory.resolve("skills");
        SkillSettingsStore store = new SkillSettingsStore(root, new SkillParser());
        SkillSource bundled = new SkillSource(
                "openallay:bundled",
                "guide/SKILL.md",
                Map.of("guide/SKILL.md", skill("guide", "bundled")),
                SkillSource.Origin.BUNDLED);
        store.createOverride(bundled);
        String before = Files.readString(root.resolve("guide/SKILL.md"));

        assertThrows(IllegalArgumentException.class, () -> store.editOverride("guide", "broken"));
        assertEquals(before, Files.readString(root.resolve("guide/SKILL.md")));
        assertThrows(IllegalArgumentException.class, () -> store.editOverride("../escape", before));
        assertThrows(IllegalArgumentException.class, () -> store.deleteOverride("../escape"));
        assertTrue(Files.notExists(temporaryDirectory.resolve("escape")));
    }

    @Test
    void bundledPackagesCannotBeDeletedAndExistingOverridesCannotBeClobbered() {
        Path root = temporaryDirectory.resolve("skills");
        SkillSettingsStore store = new SkillSettingsStore(root, new SkillParser());
        SkillSource bundled = new SkillSource(
                "openallay:bundled",
                "guide/SKILL.md",
                Map.of("guide/SKILL.md", skill("guide", "bundled")),
                SkillSource.Origin.BUNDLED);

        store.createOverride(bundled);
        assertThrows(IllegalStateException.class, () -> store.createOverride(bundled));
        assertThrows(IllegalArgumentException.class, () -> store.createOverride(new SkillSource(
                "local:test",
                "local/SKILL.md",
                Map.of("local/SKILL.md", skill("local", "local")),
                SkillSource.Origin.LOCAL)));
    }

    private static String skill(String name, String body) {
        return """
                ---
                name: %s
                description: Test skill
                allowed-tools: ""
                ---
                %s
                """.formatted(name, body);
    }
}
