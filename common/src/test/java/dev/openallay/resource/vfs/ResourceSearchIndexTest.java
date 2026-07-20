package dev.openallay.resource.vfs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResourceSearchIndexTest {
    @Test
    void globRejectsCrossMountAndTraversalPatterns() {
        assertThrows(IllegalArgumentException.class, () -> ResourceGlobPattern.compile("/**/thing"));
        assertThrows(IllegalArgumentException.class, () -> ResourceGlobPattern.compile("/item/../thing"));
        assertThrows(IllegalArgumentException.class, () -> ResourceGlobPattern.compile("item/**"));
    }

    @Test
    void recursiveGlobMatchesZeroOrManySegments() {
        ResourceGlobPattern pattern = ResourceGlobPattern.compile("/item/**/berry");
        assertTrue(pattern.matches(ResourcePath.parse("/item/berry")));
        assertTrue(pattern.matches(ResourcePath.parse("/item/example/food/berry")));
        assertFalse(pattern.matches(ResourcePath.parse("/result/example/berry")));
    }
}
