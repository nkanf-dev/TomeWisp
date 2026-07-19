package dev.tomewisp.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AgentSystemPromptTest {
    @Test
    void keepsCasualConversationToolFreeAndDefinesGroundedRecovery() {
        String prompt = AgentSystemPrompt.compose("- inspect-game-state: Inspect settings");

        assertTrue(prompt.contains("Do not call tools for greetings"));
        assertTrue(prompt.contains("Resolve a natural/localized name first"));
        assertTrue(prompt.contains("at most one materially corrected call"));
        assertTrue(prompt.contains("stop calling tools"));
        assertTrue(prompt.contains("inspect_game_state"));
        assertTrue(prompt.contains("- inspect-game-state: Inspect settings"));
        assertFalse(prompt.contains("server-hosted"));
    }

    @Test
    void suppliesAnExplicitEmptySkillCatalogWithoutChangingAuthority() {
        String prompt = AgentSystemPrompt.compose("  ");
        assertTrue(prompt.contains("No Skill metadata is currently available."));
        assertTrue(prompt.contains("Only registered read-only operations are authorized"));
    }
}
