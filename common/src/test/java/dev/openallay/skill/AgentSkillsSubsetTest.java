package dev.openallay.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AgentSkillsSubsetTest {
    @Test
    void parsesOfficialFieldsAndOpenAllayNamespacedMetadata() {
        SkillDocument document = new SkillParser().parse(source(
                "guide",
                """
                ---
                name: guide
                description: Guide the player from trusted evidence
                license: Apache-2.0
                compatibility: Minecraft 26.2 and Java 25
                metadata:
                  author: OpenAllay
                  openallay/required-mods: "ftbquests, patchouli"
                allowed-tools: "openallay:resource_grep openallay:resource_read"
                ---
                Follow evidence.
                """));

        assertEquals("Apache-2.0", document.metadata().license().orElseThrow());
        assertEquals("Minecraft 26.2 and Java 25", document.metadata().compatibility().orElseThrow());
        assertEquals("OpenAllay", document.metadata().attributes().get("author"));
        assertEquals(Set.of("ftbquests", "patchouli"), document.metadata().requiredMods());
        assertEquals(
                Set.of("openallay:resource_grep", "openallay:resource_read"),
                document.metadata().allowedTools());
        assertEquals("Ground every claim.", document.references().get("references/policy.md"));
    }

    @Test
    void enforcesAgentSkillsNamesAndTextLimits() {
        SkillParser parser = new SkillParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parse(source(
                "other", minimal("guide", "description"))));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(source(
                "bad--name", minimal("bad--name", "description"))));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(source(
                "a".repeat(65), minimal("a".repeat(65), "description"))));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(source(
                "guide", minimal("guide", "d".repeat(1025)))));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(source(
                "guide", minimal("guide", "description")
                        .replace("---\nFollow", "compatibility: " + "c".repeat(501) + "\n---\nFollow"))));
    }

    @Test
    void rejectsUnsupportedTopLevelFieldsScriptsAndNonStringMetadata() {
        SkillParser parser = new SkillParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parse(source(
                "guide", minimal("guide", "description").replace(
                        "allowed-tools:", "required-mods: [unsafe]\nallowed-tools:"))));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new SkillSource(
                "local:test",
                "guide/SKILL.md",
                Map.of(
                        "guide/SKILL.md", minimal("guide", "description"),
                        "guide/scripts/run.sh", "danger"),
                SkillSource.Origin.LOCAL)));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(source(
                "guide", minimal("guide", "description").replace(
                        "allowed-tools:", "metadata:\n  numeric: 42\nallowed-tools:"))));
    }

    @Test
    void allowedToolsRemainDependenciesAndDoNotGrantMissingTools() {
        SkillRepository repository = new SkillRepository(new SkillParser(), Set.of());
        SkillSource source = source("guide", minimal("guide", "description")
                .replace("allowed-tools: \"\"", "allowed-tools: \"danger:write\""));

        assertFalse(repository.reload(java.util.List.of(source), Set.of()));
        assertTrue(repository.metadata().isEmpty());
        assertEquals("skill_validation_failed", repository.diagnostics().getFirst().code());
    }

    private static SkillSource source(String directory, String entry) {
        return new SkillSource(
                "local:test",
                directory + "/SKILL.md",
                Map.of(
                        directory + "/SKILL.md", entry,
                        directory + "/references/policy.md", "Ground every claim."),
                SkillSource.Origin.LOCAL);
    }

    private static String minimal(String name, String description) {
        return """
                ---
                name: %s
                description: %s
                allowed-tools: ""
                ---
                Follow evidence.
                """.formatted(name, description);
    }
}
