package dev.openallay.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class BundledSkillsTest {
    @Test
    void everyBundledSkillIsValidGroundedAndProgressivelyLoadable() {
        Set<String> tools = Set.of(
                "openallay:resolve_resource",
                "openallay:find_recipes",
                "openallay:search_recipes",
                "openallay:get_recipe",
                "openallay:find_item_usages",
                "openallay:inspect_inventory",
                "openallay:calculate_craftability",
                "openallay:inspect_game_state",
                "openallay:list_knowledge_sources",
                "openallay:search_knowledge",
                "openallay:get_knowledge_document",
                "openallay:get_patchouli_multiblock");
        SkillRepository repository = new SkillRepository(new SkillParser(), tools);
        assertTrue(repository.reload(new BundledSkillLoader().load(), Set.of("ftbquests")));
        assertEquals(BundledSkillLoader.NAMES.stream().sorted().toList(), repository.metadata().stream()
                .map(SkillMetadata::name).sorted().toList());
        for (SkillMetadata metadata : repository.metadata()) {
            SkillDocument document = repository.find(metadata.name()).orElseThrow();
            assertFalse(document.instructions().isBlank());
            String lower = document.instructions().toLowerCase(java.util.Locale.ROOT);
            assertTrue(lower.contains("unavailable") || lower.contains("不可用"));
            assertFalse(repository.metadataPrompt().contains(document.instructions()));
        }

        SkillDocument analysis = repository.find("analyze-game-data").orElseThrow();
        assertEquals(Set.of(
                "references/datasets.md",
                "references/examples.md",
                "references/pipelines.md"), analysis.references().keySet());
        assertTrue(repository.metadataPrompt().contains("<name>analyze-game-data</name>"));
        assertFalse(repository.metadataPrompt().contains("pipeline is JSON data"));

        SkillDocument fallback = repository.find("answer-modded-minecraft-question")
                .orElseThrow();
        assertTrue(fallback.instructions().contains("Choose one branch"));
        assertTrue(fallback.instructions().contains("Do not search recipes or guides"));
        SkillDocument gameState = repository.find("inspect-game-state").orElseThrow();
        assertTrue(gameState.instructions().contains("current Tool definitions"));
        assertFalse(gameState.instructions().contains("`openallay:inspect_game_state`"));
    }
}
