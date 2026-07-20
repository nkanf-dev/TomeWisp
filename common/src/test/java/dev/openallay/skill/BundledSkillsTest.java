package dev.openallay.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class BundledSkillsTest {
    @Test
    void everyBundledSkillUsesOnlyTheFiveVfsToolsAndIsProgressivelyLoadable() {
        Set<String> tools = Set.of(
                "openallay:resource_list",
                "openallay:resource_read",
                "openallay:resource_glob",
                "openallay:resource_grep",
                "openallay:resource_query");
        SkillRepository repository = new SkillRepository(new SkillParser(), tools);
        assertTrue(repository.reload(new BundledSkillLoader().load(), Set.of("ftbquests")));
        assertEquals(BundledSkillLoader.NAMES.stream().sorted().toList(), repository.metadata().stream()
                .map(SkillMetadata::name).sorted().toList());
        for (SkillMetadata metadata : repository.metadata()) {
            SkillDocument document = repository.find(metadata.name()).orElseThrow();
            assertFalse(document.instructions().isBlank());
            String lower = document.instructions().toLowerCase(java.util.Locale.ROOT);
            assertTrue(lower.contains("unavailable") || lower.contains("不可用"));
            assertEquals(tools, metadata.allowedTools());
            assertTrue(metadata.references().stream().allMatch(path -> path.startsWith("references/")));
            assertFalse(repository.metadataPrompt().contains(document.instructions()));
        }

        SkillDocument analysis = repository.find("analyze-game-data").orElseThrow();
        assertEquals(Set.of(
                "references/datasets.md",
                "references/examples.md",
                "references/pipelines.md"), analysis.references().keySet());
        assertTrue(repository.metadataPrompt().contains("<name>analyze-game-data</name>"));
        assertFalse(repository.metadataPrompt().contains("/@schema"));

        SkillDocument fallback = repository.find("answer-modded-minecraft-question")
                .orElseThrow();
        assertTrue(fallback.instructions().contains("Choose one branch"));
        assertTrue(fallback.instructions().contains("/result"));
        SkillDocument gameState = repository.find("inspect-game-state").orElseThrow();
        assertTrue(gameState.instructions().contains("/game"));
        assertTrue(gameState.instructions().contains("Batch"));

        String corpus = repository.metadata().stream()
                .map(metadata -> repository.find(metadata.name()).orElseThrow())
                .map(document -> document.instructions() + "\n"
                        + String.join("\n", document.references().values()))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(corpus.contains("poison"));
        assertTrue(corpus.contains("saturation"));
        assertTrue(corpus.contains("missing recipe"));
        assertTrue(corpus.contains("guide"));
        assertTrue(corpus.contains("/mod/<modid>/raw"));
        assertTrue(corpus.contains("mixed client/server"));
        assertTrue(corpus.contains("/@schema"));
        assertTrue(corpus.contains("resource_read"));
        assertTrue(corpus.contains("cursor"));
        assertTrue(corpus.contains("/result"));
        assertFalse(corpus.contains("resolve_resource"));
        assertFalse(corpus.contains("search_recipes"));
        assertFalse(corpus.contains("inspect_game_state"));
    }
}
