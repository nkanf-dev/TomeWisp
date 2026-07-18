package dev.tomewisp.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SkillRepositoryTest {
    @Test
    void progressivelyLoadsBodyAndDeclaredReferences() {
        SkillRepository repository = repository();
        assertTrue(repository.reload(java.util.List.of(valid("answer-guide", "Secret body")), Set.of()));

        assertFalse(repository.metadataPrompt().contains("Secret body"));
        SkillDocument loaded = repository.find("answer-guide").orElseThrow();
        assertEquals("Secret body", loaded.instructions());
        assertEquals("Ground every claim.", loaded.references().get("references/policy.md"));
    }

    @Test
    void failedReloadRetainsLastGoodSnapshot() {
        SkillRepository repository = repository();
        assertTrue(repository.reload(java.util.List.of(valid("answer-guide", "first")), Set.of()));
        SkillSource invalid = new SkillSource(
                "bad-pack",
                "bad/SKILL.md",
                Map.of("bad/SKILL.md", frontmatter("answer-guide", "second", "[unknown:tool]")));

        assertFalse(repository.reload(java.util.List.of(invalid), Set.of()));
        assertEquals("first", repository.find("answer-guide").orElseThrow().instructions());
        assertEquals("skill_validation_failed", repository.diagnostics().getFirst().code());
    }

    @Test
    void rejectsScriptsUrlsAndMissingReferences() {
        SkillRepository repository = repository();
        SkillSource scripted = new SkillSource(
                "scripted",
                "s/skill.md",
                Map.of(
                        "s/skill.md", frontmatter("scripted", "body", "[tomewisp:find_recipes]"),
                        "s/scripts/run.sh", "danger"));
        assertFalse(repository.reload(java.util.List.of(scripted), Set.of()));

        String remote = frontmatter("remote", "body", "[tomewisp:find_recipes]")
                .replace("references/policy.md", "https://example.invalid/policy.md");
        assertFalse(repository.reload(java.util.List.of(new SkillSource(
                "remote", "r/SKILL.md", Map.of("r/SKILL.md", remote))), Set.of()));
    }

    @Test
    void filtersSkillsWhoseRequiredModsAreAbsent() {
        SkillRepository repository = repository();
        SkillSource source = valid("quest-guide", "body");
        String entry = source.files().get(source.entryPath()).replace(
                "required-mods: []", "required-mods: [ftbquests]");
        source = new SkillSource(source.provenance(), source.entryPath(), Map.of(
                source.entryPath(), entry,
                "quest-guide/references/policy.md", "Ground every claim."));

        assertTrue(repository.reload(java.util.List.of(source), Set.of()));
        assertTrue(repository.metadata().isEmpty());
        assertEquals("required_mod_unavailable", repository.diagnostics().getFirst().code());
    }

    @Test
    void snapshotFiltersDisabledSkillsAndDoesNotObserveLaterReload() {
        SkillRepository repository = repository();
        assertTrue(repository.reload(java.util.List.of(
                valid("original-skill", "original"),
                valid("disabled-skill", "disabled")), Set.of()));

        SkillCatalogSnapshot snapshot = repository.snapshot(Set.of("disabled-skill"));
        assertTrue(repository.reload(
                java.util.List.of(valid("replacement-skill", "replacement")), Set.of()));

        assertTrue(snapshot.find("original-skill").isPresent());
        assertTrue(snapshot.find("disabled-skill").isEmpty());
        assertTrue(snapshot.find("replacement-skill").isEmpty());
        assertFalse(snapshot.metadataPrompt().contains("disabled-skill"));
        assertTrue(repository.find("replacement-skill").isPresent());
    }

    private static SkillRepository repository() {
        return new SkillRepository(new SkillParser(), Set.of("tomewisp:find_recipes"));
    }

    private static SkillSource valid(String name, String body) {
        return new SkillSource(
                "test-pack",
                name + "/SKILL.md",
                Map.of(
                        name + "/SKILL.md", frontmatter(name, body, "[tomewisp:find_recipes]"),
                        name + "/references/policy.md", "Ground every claim."));
    }

    private static String frontmatter(String name, String body, String tools) {
        return """
                ---
                name: %s
                description: Answer a guide question
                required-mods: []
                allowed-tools: %s
                references:
                  - references/policy.md
                ---
                %s
                """.formatted(name, tools, body);
    }
}
