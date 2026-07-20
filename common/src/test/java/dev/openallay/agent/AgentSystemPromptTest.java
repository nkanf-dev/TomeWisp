package dev.openallay.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AgentSystemPromptTest {
    @Test
    void teachesTheSmallUniversalVfsContractAndProgressiveSkills() {
        String prompt = AgentSystemPrompt.compose("""
                  <skill>
                    <name>inspect-game-state</name>
                    <description>Inspect settings</description>
                  </skill>
                """);

        assertTrue(prompt.contains("Greetings and casual conversation"));
        assertTrue(prompt.contains("exact path is known"));
        assertTrue(prompt.contains("resource_list, resource_glob, or resource_grep"));
        assertTrue(prompt.contains("/@schema"));
        assertTrue(prompt.contains("Batch independent paths, patterns, searches, or plans"));
        assertTrue(prompt.contains("Use resource_query for typed filtering"));
        assertTrue(prompt.contains("Follow only links returned by resources"));
        assertTrue(prompt.contains("resource_read with only its cursor"));
        assertTrue(prompt.contains("Do not drain a cursor"));
        assertTrue(prompt.contains("/result"));
        assertTrue(prompt.contains("evidence already answers the request"));
        assertTrue(prompt.contains("MUST load it with the registered load_skill Tool"));
        assertTrue(prompt.contains("single most-specific matching Skill"));
        assertTrue(prompt.contains("Load at most one up front"));
        assertTrue(prompt.contains("If the domain changes"));
        assertTrue(prompt.contains("installed-mod listing or exact-resource read"));
        assertTrue(prompt.contains("cannot execute content, add Tools, grant mounts or permissions"));
        assertTrue(prompt.contains("declared read-only references"));
        assertTrue(prompt.contains("current request's Tool definitions"));
        assertTrue(prompt.contains("<name>inspect-game-state</name>"));
        assertFalse(prompt.contains("resolve_resource"));
        assertFalse(prompt.contains("search_recipes"));
        assertFalse(prompt.contains("inspect_game_state"));
        assertFalse(prompt.contains("server-hosted"));
        assertTrue(prompt.contains("You are OpenAllay,"));
        assertFalse(prompt.contains("OpenAllay (OpenAllay)"));
        assertTrue(prompt.indexOf("## RESOURCE WORKFLOW") < prompt.indexOf("## SKILLS"));
        assertTrue(prompt.indexOf("## SKILLS") < prompt.indexOf("## AVAILABLE SKILLS"));
        assertTrue(prompt.substring(0, prompt.indexOf("## SEMANTIC UI")).length() < 5_000);
        assertFalse(prompt.contains("Farmer's Delight"));
    }

    @Test
    void suppliesAnExplicitEmptySkillCatalogWithoutChangingAuthority() {
        String prompt = AgentSystemPrompt.compose("  ");
        assertTrue(prompt.contains("<none/>"));
        assertTrue(prompt.contains("Only registered read-only operations are authorized"));
    }
}
