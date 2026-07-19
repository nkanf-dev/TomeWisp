package dev.openallay;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.openallay.platform.PlatformService;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class BuiltinToolRegistrationTest {
    @Test
    void registersTheGroundedRecipeWorkflowAndCompatibilityAlias() {
        Set<String> toolIds = OpenAllayBootstrap.builtinTools(testPlatform()).stream()
                .map(tool -> tool.descriptor().id())
                .collect(Collectors.toSet());

        assertTrue(toolIds.containsAll(Set.of(
                "openallay:search_recipes",
                "openallay:get_recipe",
                "openallay:find_item_usages",
                "openallay:inspect_inventory",
                "openallay:calculate_craftability",
                "openallay:find_recipes")));
        assertTrue(toolIds.contains("openallay:inspect_game_state"));
        assertFalse(toolIds.contains("openallay:platform_info"));
        assertFalse(toolIds.contains("openallay:player_context"));
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
