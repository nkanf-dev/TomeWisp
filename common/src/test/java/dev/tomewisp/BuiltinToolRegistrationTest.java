package dev.tomewisp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tomewisp.platform.PlatformService;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class BuiltinToolRegistrationTest {
    @Test
    void registersTheGroundedRecipeWorkflowAndCompatibilityAlias() {
        Set<String> toolIds = TomeWispBootstrap.builtinTools(testPlatform()).stream()
                .map(tool -> tool.descriptor().id())
                .collect(Collectors.toSet());

        assertTrue(toolIds.containsAll(Set.of(
                "tomewisp:search_recipes",
                "tomewisp:get_recipe",
                "tomewisp:find_item_usages",
                "tomewisp:inspect_inventory",
                "tomewisp:calculate_craftability",
                "tomewisp:find_recipes")));
        assertTrue(toolIds.contains("tomewisp:inspect_game_state"));
        assertFalse(toolIds.contains("tomewisp:platform_info"));
        assertFalse(toolIds.contains("tomewisp:player_context"));
    }

    private static PlatformService testPlatform() {
        return new PlatformService() {
            @Override public String platformName() { return "common-test"; }
            @Override public String gameVersion() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return false; }
            @Override public boolean isDevelopmentEnvironment() { return true; }
        };
    }
}
