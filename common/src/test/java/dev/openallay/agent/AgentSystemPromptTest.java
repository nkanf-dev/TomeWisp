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
        assertTrue(prompt.contains("Collection-wide ranking"));
        assertTrue(prompt.contains("ALWAYS matches analyze-game-data"));
        assertTrue(prompt.contains("exact known object or exact ID"));
        assertTrue(prompt.contains("merely because it lists the same Tool"));
        assertTrue(prompt.contains("direct-Tool exception"));
        assertTrue(prompt.contains("Never repeat a successful call"));
        assertTrue(prompt.contains("Do not spend calls rediscovering mc root names"));
        assertTrue(prompt.contains("Make at most one focused discovery call"));
        assertTrue(prompt.contains("first run_javascript call must perform the complete"));
        assertTrue(prompt.contains("scope: complete JavaScript result"));
        assertTrue(prompt.contains("terminal for that analysis"));
        assertTrue(prompt.contains(
                "calculate_craftability is only for a player asking whether"));
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
        assertTrue(prompt.contains("Only registered operations are authorized"));
        assertTrue(prompt.contains("JavaScript is isolated data analysis, not a shell"));
    }
}
