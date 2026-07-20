package dev.openallay.resource.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ResourcePathTest {
    @Test
    void canonicalPathRoundTripsEncodedIdentity() {
        ResourcePath path = ResourcePath.of("recipe", "farmersdelight", "cooking/apple_cider", "@schema");
        String encoded = "/recipe/farmersdelight/cooking%2Fapple_cider/@schema";
        assertEquals(encoded, path.toString());
        assertEquals(path, ResourcePath.parse(encoded));
        assertEquals("recipe", path.mount());
    }

    @Test
    void childAndParentPreserveCanonicalSegments() {
        ResourcePath root = ResourcePath.parse("/item/minecraft/apple");
        assertEquals("/item/minecraft/apple/damage", root.child("damage").toString());
        assertEquals(root, root.child("damage").parent());
    }

    @Test
    void rejectsTraversalMalformedAndNonCanonicalPaths() {
        for (String value : new String[] {
                "item/minecraft/apple", "/", "/item//apple", "/item/../apple",
                "/item/./apple", "/item\\apple", "/item/apple/", "/item/%2f", "/item/%GG"
        }) {
            assertThrows(IllegalArgumentException.class, () -> ResourcePath.parse(value), value);
        }
    }
}
