package dev.tomewisp.settings.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.skill.SkillSource;
import dev.tomewisp.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SkillSettingsBackendTest {
    @TempDir Path temporaryDirectory;

    @Test
    void savingBundledSkillCreatesValidatedLocalOverride() throws IOException {
        Path root = temporaryDirectory.resolve("skills");
        SkillRepository repository = repository();
        SkillSettingsBackend backend = backend(root, repository);

        SkillSettingsView initial = backend.currentView();
        assertEquals(SkillSource.Origin.BUNDLED, initial.find("guide").orElseThrow().origin());
        assertTrue(initial.find("guide").orElseThrow().createsOverrideOnSave());

        SkillSettingsView saved = success(backend.saveOverride("guide", skill("guide", "local body")));

        SkillSettingsView.Skill guide = saved.find("guide").orElseThrow();
        assertEquals(SkillSource.Origin.LOCAL, guide.origin());
        assertEquals("local body", guide.body());
        assertTrue(guide.overridePresent());
        assertEquals("local body", repository.find("guide").orElseThrow().instructions());
        assertEquals(skill("guide", "local body"), Files.readString(root.resolve("guide/SKILL.md")));
    }

    @Test
    void invalidEditLeavesPriorFileAndPublishedDocumentUnchanged() throws IOException {
        Path root = temporaryDirectory.resolve("skills");
        SkillRepository repository = repository();
        SkillSettingsBackend backend = backend(root, repository);
        success(backend.saveOverride("guide", skill("guide", "valid local")));
        String before = Files.readString(root.resolve("guide/SKILL.md"));

        failure(backend.saveOverride("guide", "not frontmatter"));

        assertEquals(before, Files.readString(root.resolve("guide/SKILL.md")));
        assertEquals("valid local", repository.find("guide").orElseThrow().instructions());
        assertEquals("valid local", backend.currentView().find("guide").orElseThrow().body());
    }

    @Test
    void dependencyRejectedEditRollsBackAndExternalInvalidityRetainsLastValidDiagnostic()
            throws IOException {
        Path root = temporaryDirectory.resolve("skills");
        SkillRepository repository = repository();
        SkillSettingsBackend backend = backend(root, repository);
        success(backend.saveOverride("guide", skill("guide", "valid local")));
        String before = Files.readString(root.resolve("guide/SKILL.md"));

        String unavailableTool = before.replace(
                "allowed-tools: \"\"", "allowed-tools: \"unknown:tool\"");
        ToolResult.Failure<SkillSettingsView> rejected = failure(
                backend.saveOverride("guide", unavailableTool));
        assertEquals("skill_override_dependency_unavailable", rejected.code());
        assertEquals(before, Files.readString(root.resolve("guide/SKILL.md")));

        Files.writeString(root.resolve("guide/SKILL.md"), "externally broken");
        SkillSettingsView retained = success(backend.reloadSkills());
        assertEquals("valid local", retained.find("guide").orElseThrow().body());
        assertEquals(before, retained.find("guide").orElseThrow().markdown());
        assertEquals("skill_validation_failed", retained.diagnostics().getFirst().code());
    }

    @Test
    void deletingLocalOverrideRestoresReadOnlyBundledDocument() {
        Path root = temporaryDirectory.resolve("skills");
        SkillRepository repository = repository();
        SkillSettingsBackend backend = backend(root, repository);
        success(backend.saveOverride("guide", skill("guide", "local")));

        SkillSettingsView restored = success(backend.deleteOverride("guide"));

        SkillSettingsView.Skill guide = restored.find("guide").orElseThrow();
        assertEquals(SkillSource.Origin.BUNDLED, guide.origin());
        assertEquals("bundled body", guide.body());
        assertFalse(guide.overridePresent());
        assertFalse(Files.exists(root.resolve("guide")));
    }

    @Test
    void restartLoadsPersistedOverrideFromExternalMarkdownPackage() {
        Path root = temporaryDirectory.resolve("skills");
        SkillSettingsBackend first = backend(root, repository());
        success(first.saveOverride("guide", skill("guide", "survives restart")));

        SkillRepository restartedRepository = repository();
        SkillSettingsBackend restarted = backend(root, restartedRepository);

        SkillSettingsView.Skill guide = restarted.currentView().find("guide").orElseThrow();
        assertEquals(SkillSource.Origin.LOCAL, guide.origin());
        assertEquals("survives restart", guide.body());
        assertTrue(guide.overridePresent());
        assertEquals("survives restart", restartedRepository.find("guide").orElseThrow().instructions());
    }

    private SkillSettingsBackend backend(Path root, SkillRepository repository) {
        return new SkillSettingsBackend(
                root,
                repository,
                new SkillParser(),
                List.of(bundled()),
                Set.of());
    }

    private static SkillRepository repository() {
        return new SkillRepository(new SkillParser(), Set.of());
    }

    private static SkillSource bundled() {
        return new SkillSource(
                "tomewisp:bundled",
                "guide/SKILL.md",
                Map.of(
                        "guide/SKILL.md", skill("guide", "bundled body"),
                        "guide/references/facts.md", "Ground facts."),
                SkillSource.Origin.BUNDLED);
    }

    private static String skill(String name, String body) {
        return """
                ---
                name: %s
                description: Guide answers
                allowed-tools: ""
                ---
                %s
                """.formatted(name, body);
    }

    @SuppressWarnings("unchecked")
    private static SkillSettingsView success(ToolResult<SkillSettingsView> result) {
        return ((ToolResult.Success<SkillSettingsView>)
                        assertInstanceOf(ToolResult.Success.class, result))
                .value();
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<SkillSettingsView> failure(
            ToolResult<SkillSettingsView> result) {
        return (ToolResult.Failure<SkillSettingsView>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
