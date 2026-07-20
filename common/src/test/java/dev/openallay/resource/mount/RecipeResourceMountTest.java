package dev.openallay.resource.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.testing.GroundedTestFixtures;
import org.junit.jupiter.api.Test;

final class RecipeResourceMountTest {
    @Test
    void preservesExactIdentityEvidenceLinksAndNativePresentation() {
        var snapshot = new RecipeResourceMount(GroundedTestFixtures::recipeSnapshot).snapshot();
        var node = snapshot.nodes().get(ResourcePath.parse("/recipe/minecraft/iron_block"));

        assertEquals(GroundedTestFixtures.serverEvidence(), node.evidence());
        assertEquals(ResourcePresentation.Kind.RECIPE, node.presentation().kind());
        assertEquals("minecraft:iron_block", node.presentation().references().get("recipeId"));
        assertTrue(node.links().stream().anyMatch(link ->
                link.relation().equals("ingredient")
                        && link.target().equals(ResourcePath.parse("/item/minecraft/iron_ingot"))));
        ResourceValue.RecordValue record = (ResourceValue.RecordValue) node.truth();
        assertEquals(new ResourceValue.Scalar("minecraft:recipe_manager"), record.fields().get("source"));
        assertTrue(snapshot.nodes().containsKey(
                ResourcePath.parse("/recipe/minecraft/iron_block/@schema")));
    }
}
