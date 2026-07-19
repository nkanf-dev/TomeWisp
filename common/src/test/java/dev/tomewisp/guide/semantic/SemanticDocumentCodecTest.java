package dev.tomewisp.guide.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.context.RecipeReference;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SemanticDocumentCodecTest {
    private final SemanticDocumentCodec codec = new SemanticDocumentCodec();
    private int nextId;

    @Test
    void roundTripsEveryNodeAndControlledComponentWithoutParserTypes() {
        RecipeReference recipe = SemanticReferenceValidatorTest.recipe();
        String diagnosticNode = id();
        SemanticReference grounded = new SemanticReference(
                SemanticReferenceKind.RECIPE,
                RecipeSemanticHandle.encode(recipe),
                "Iron block",
                true,
                "tool-1");
        List<SemanticBlock> blocks = List.of(
                new SemanticBlock.Heading(diagnosticNode, 2, List.of(
                        new SemanticInline.Text(id(), "Heading "),
                        new SemanticInline.Strong(id(), List.of(
                                new SemanticInline.Text(id(), "strong"))))),
                new SemanticBlock.Paragraph(id(), List.of(
                        new SemanticInline.Emphasis(id(), List.of(
                                new SemanticInline.Text(id(), "emphasis"))),
                        new SemanticInline.Break(id(), true),
                        new SemanticInline.Code(id(), "code"),
                        new SemanticInline.Reference(id(), grounded))),
                new SemanticBlock.ListBlock(id(), true, 2, List.of(List.of(
                        new SemanticBlock.Paragraph(id(), List.of(
                                new SemanticInline.Text(id(), "item")))))),
                new SemanticBlock.Quote(id(), List.of(
                        new SemanticBlock.CodeBlock(id(), "java", "value"))),
                new SemanticBlock.Table(
                        id(),
                        new SemanticBlock.TableRow(List.of(new SemanticBlock.TableCell(
                                SemanticBlock.Alignment.CENTER,
                                List.of(new SemanticInline.Text(id(), "head"))))),
                        List.of(new SemanticBlock.TableRow(List.of(new SemanticBlock.TableCell(
                                SemanticBlock.Alignment.RIGHT,
                                List.of(new SemanticInline.Text(id(), "cell"))))))),
                new SemanticBlock.ThematicBreak(id()),
                component(new RichComponent.ItemRow(id(), List.of(
                        new RichComponent.Item(
                                "minecraft:iron_block", 2, "Iron", "tool-1")),
                        "Items", "Item narration")),
                component(new RichComponent.RecipeGrid(
                        id(), recipe, "tool-1", "Recipe", "Grid", "Grid narration")),
                component(new RichComponent.IngredientCheck(id(), List.of(
                        new RichComponent.Ingredient(
                                "minecraft:iron_block", 3, 2, "Iron", "tool-1")),
                        "Ingredients", "Ingredient narration")),
                component(new RichComponent.CraftabilitySummary(
                        id(), recipe, "tool-1", false, true, 1, 0,
                        "Cannot craft", "Craftability narration")),
                component(new RichComponent.ProgressSteps(id(), List.of(
                        new RichComponent.Step(
                                "gather", "Gather", RichComponent.StepState.ACTIVE)),
                        "Progress", "Progress narration")),
                component(new RichComponent.SourceSummary(id(), List.of(
                        new RichComponent.Source(
                                "minecraft:recipe_manager", "Recipes", "tool-1")),
                        "Sources", "Source narration")),
                component(new RichComponent.StatusBadge(
                        id(), RichComponent.BadgeState.WARNING, "Missing",
                        "Warning", "Badge narration")),
                component(new RichComponent.ChoiceGroup(
                        id(), "Choose", List.of(
                                new RichComponent.Choice("first", "First")),
                        "Choice", "Choice narration")));
        SemanticDocument document = SemanticDocument.of(
                blocks, List.of(new SemanticDiagnostic(
                        "semantic_content_unsupported", diagnosticNode)));

        String encoded = codec.encode(document);

        assertEquals(document, codec.decode(encoded));
        org.junit.jupiter.api.Assertions.assertFalse(encoded.contains("org.commonmark"));
    }

    @Test
    void rejectsUnknownVersionsKeysAndFallbackMismatch() {
        JsonObject encoded = codec.encodeObject(new SemanticMessageParser().parse("Hello"));

        JsonObject version = encoded.deepCopy();
        version.addProperty("schemaVersion", 2);
        JsonObject unknown = encoded.deepCopy();
        unknown.addProperty("providerBody", "secret");
        JsonObject fallback = encoded.deepCopy();
        fallback.addProperty("fallbackText", "changed");

        assertThrows(IllegalArgumentException.class, () -> codec.decode(version.toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknown.toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(fallback.toString()));

        JsonObject badNode = JsonParser.parseString(codec.encode(
                new SemanticMessageParser().parse("Hello"))).getAsJsonObject();
        badNode.getAsJsonArray("blocks").get(0).getAsJsonObject()
                .addProperty("callback", "java.lang.Runtime");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(badNode.toString()));
    }

    private static SemanticBlock.Component component(RichComponent component) {
        return new SemanticBlock.Component(component.nodeId(), component);
    }

    private String id() {
        return "%064x".formatted(++nextId);
    }
}
