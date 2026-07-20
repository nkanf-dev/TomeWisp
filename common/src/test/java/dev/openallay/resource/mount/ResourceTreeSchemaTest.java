package dev.openallay.resource.mount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.testing.GroundedTestFixtures;
import org.junit.jupiter.api.Test;

final class ResourceTreeSchemaTest {
    @Test
    void everyTypedRecordPublishesItsDiscoveredFieldVocabulary() {
        var snapshot = new RecipeResourceMount(GroundedTestFixtures::recipeSnapshot).snapshot();
        ResourcePath schemaPath = ResourcePath.parse("/recipe/minecraft/iron_block/@schema");
        ResourceValue.RecordValue schema = (ResourceValue.RecordValue) snapshot.nodes().get(schemaPath).truth();
        String rendered = schema.toString();
        assertTrue(rendered.contains("/ingredients/*/count"));
        assertTrue(rendered.contains("aggregate"));
        assertTrue(rendered.contains("/processing/duration_ticks"));
        assertTrue(snapshot.nodes().get(ResourcePath.parse("/recipe/minecraft/iron_block"))
                .children().stream().anyMatch(entry -> entry.path().equals(schemaPath)));

        ResourceValue.RecordValue collectionSchema = (ResourceValue.RecordValue) snapshot.nodes()
                .get(ResourcePath.parse("/recipe/@schema")).truth();
        assertTrue(collectionSchema.toString().contains("/outputs/*/item"));
        assertEquals(ResourceValue.Scalar.number(1), collectionSchema.fields().get("total_rows"));
    }
}
