package dev.tomewisp.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FilesystemSkillLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void scansOnlyDirectSkillDirectoriesWithUppercaseEntry() throws IOException {
        Path root = temporaryDirectory.resolve("skills");
        write(root.resolve("valid/SKILL.md"), skill("valid", "valid"));
        write(root.resolve("lower/skill.md"), skill("lower", "lower"));
        write(root.resolve("nested/group/ignored/SKILL.md"), skill("ignored", "ignored"));

        FilesystemSkillLoader.LoadResult result = new FilesystemSkillLoader().load(root);

        assertEquals(1, result.sources().size());
        assertEquals("valid/SKILL.md", result.sources().getFirst().entryPath());
        assertEquals(SkillSource.Origin.LOCAL, result.sources().getFirst().origin());
        assertTrue(result.rejected().stream().anyMatch(rejected -> rejected.skillName().equals("lower")));
    }

    @Test
    void rejectsScriptsUnsupportedRootFilesAndEscapedSymlinksIndependently() throws IOException {
        Path root = temporaryDirectory.resolve("skills");
        write(root.resolve("good/SKILL.md"), skill("good", "good"));
        write(root.resolve("scripted/SKILL.md"), skill("scripted", "scripted"));
        write(root.resolve("scripted/scripts/run.sh"), "danger");
        write(root.resolve("unsupported/SKILL.md"), skill("unsupported", "unsupported"));
        write(root.resolve("unsupported/run.jar"), "danger");
        write(temporaryDirectory.resolve("outside.txt"), "outside");
        write(root.resolve("escaped/SKILL.md"), skill("escaped", "escaped"));
        Files.createSymbolicLink(
                root.resolve("escaped/references"), temporaryDirectory.resolve("outside.txt"));

        FilesystemSkillLoader.LoadResult result = new FilesystemSkillLoader().load(root);

        assertEquals(java.util.List.of("good/SKILL.md"), result.sources().stream()
                .map(SkillSource::entryPath).toList());
        assertEquals(
                java.util.Set.of("scripted", "unsupported", "escaped"),
                result.rejected().stream().map(FilesystemSkillLoader.RejectedSkill::skillName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.rejected().stream()
                .allMatch(rejected -> rejected.diagnostic().provenance().startsWith("local:")));
    }

    @Test
    void localOverridesBundledAndInvalidReloadRetainsLastValidThenDeletionRevealsBundled()
            throws IOException {
        Path root = temporaryDirectory.resolve("skills");
        SkillSource bundled = new SkillSource(
                "tomewisp:bundled",
                "guide/SKILL.md",
                java.util.Map.of("guide/SKILL.md", skill("guide", "bundled")),
                SkillSource.Origin.BUNDLED);
        write(root.resolve("guide/SKILL.md"), skill("guide", "local"));
        FilesystemSkillLoader loader = new FilesystemSkillLoader();
        SkillRepository repository = new SkillRepository(new SkillParser(), java.util.Set.of());

        assertTrue(repository.reload(java.util.List.of(bundled), loader.load(root), java.util.Set.of()));
        assertEquals("local", repository.find("guide").orElseThrow().instructions());
        assertEquals(SkillSource.Origin.LOCAL, repository.find("guide").orElseThrow().metadata().origin());

        write(root.resolve("guide/SKILL.md"), "not frontmatter");
        assertTrue(repository.reload(java.util.List.of(bundled), loader.load(root), java.util.Set.of()));
        assertEquals("local", repository.find("guide").orElseThrow().instructions());
        assertEquals("skill_validation_failed", repository.diagnostics().getFirst().code());

        write(root.resolve("guide/SKILL.md"), skill("guide", "would-be-new"));
        write(root.resolve("guide/scripts/run.sh"), "danger");
        assertTrue(repository.reload(java.util.List.of(bundled), loader.load(root), java.util.Set.of()));
        assertEquals("local", repository.find("guide").orElseThrow().instructions());

        Files.delete(root.resolve("guide/scripts/run.sh"));
        Files.delete(root.resolve("guide/scripts"));
        Files.delete(root.resolve("guide/SKILL.md"));
        Files.delete(root.resolve("guide"));
        assertTrue(repository.reload(java.util.List.of(bundled), loader.load(root), java.util.Set.of()));
        assertEquals("bundled", repository.find("guide").orElseThrow().instructions());
        assertEquals(SkillSource.Origin.BUNDLED, repository.find("guide").orElseThrow().metadata().origin());
    }

    private static void write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
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
