package dev.openallay.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AgentSystemPromptTest {
    @Test
    void keepsCasualConversationToolFreeAndDefinesGroundedRecovery() {
        String prompt = AgentSystemPrompt.compose("""
                  <skill>
                    <name>inspect-game-state</name>
                    <description>Inspect settings</description>
                  </skill>
                """);

        assertTrue(prompt.contains("Greetings and casual conversation"));
        assertTrue(prompt.contains("MUST call the registered load_skill Tool"));
        assertTrue(prompt.contains("single most-specific matching Skill"));
        assertTrue(prompt.contains("Load at most one up front"));
        assertTrue(prompt.contains("task domain changes"));
        assertTrue(prompt.contains("previously loaded unrelated Skill"));
        assertTrue(prompt.contains("simple, obvious one-call lookup"));
        assertTrue(prompt.contains("merely because it lists the same Tool"));
        assertTrue(prompt.contains("several fields that one Tool returns"));
        assertTrue(prompt.contains("Never repeat a successful call"));
        assertTrue(prompt.contains("current request's Tool definitions"));
        assertFalse(prompt.contains("use inspect_game_state"));
        assertTrue(prompt.contains("<name>inspect-game-state</name>"));
        assertFalse(prompt.contains("server-hosted"));
        assertTrue(prompt.contains("You are OpenAllay,"));
        assertFalse(prompt.contains("OpenAllay (OpenAllay)"));
        assertTrue(prompt.indexOf("## SKILL PREFLIGHT") < prompt.indexOf("## AVAILABLE SKILLS"));
        assertTrue(prompt.indexOf("## AVAILABLE SKILLS") < prompt.indexOf("## EXECUTION"));
    }

    @Test
    void suppliesAnExplicitEmptySkillCatalogWithoutChangingAuthority() {
        String prompt = AgentSystemPrompt.compose("  ");
        assertTrue(prompt.contains("<none/>"));
        assertTrue(prompt.contains("Only registered read-only operations are authorized"));
    }
}
