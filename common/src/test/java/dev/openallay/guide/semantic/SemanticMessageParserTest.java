package dev.openallay.guide.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class SemanticMessageParserTest {
    private final SemanticMessageParser parser = new SemanticMessageParser();

    @Test
    void translatesSupportedCommonMarkIntoClosedProductTypes() {
        SemanticDocument document = parser.parse("""
                # Crafting **guide**

                Use *care* and `JEI`.

                > Check the workstation.

                1. Gather iron
                2. Craft the part

                | Input | Count |
                | :-- | --: |
                | Iron | 3 |

                ```text
                no command execution
                ```
                """);

        assertEquals(SemanticDocument.SCHEMA_VERSION, document.schemaVersion());
        assertInstanceOf(SemanticBlock.Heading.class, document.blocks().get(0));
        SemanticBlock.Paragraph paragraph = assertInstanceOf(
                SemanticBlock.Paragraph.class, document.blocks().get(1));
        assertTrue(paragraph.content().stream().anyMatch(SemanticInline.Emphasis.class::isInstance));
        assertTrue(paragraph.content().stream().anyMatch(SemanticInline.Code.class::isInstance));
        assertTrue(document.blocks().stream().anyMatch(SemanticBlock.Quote.class::isInstance));
        assertTrue(document.blocks().stream().anyMatch(SemanticBlock.ListBlock.class::isInstance));
        assertTrue(document.blocks().stream().anyMatch(SemanticBlock.Table.class::isInstance));
        assertTrue(document.blocks().stream().anyMatch(SemanticBlock.CodeBlock.class::isInstance));
        assertTrue(document.fallbackText().contains("Crafting guide"));
        assertTrue(document.fallbackText().contains("Input | Count"));
        assertTrue(document.diagnostics().isEmpty());
    }

    @Test
    void htmlLinksAndImagesStayReadableButCannotBecomeActions() {
        String source = """
                Open [site](https://example.invalid) or ![icon](https://example.invalid/x.png).

                <button onclick="danger()">Do not run</button>
                """;

        SemanticDocument document = parser.parse(source);

        assertEquals(List.of(SemanticBlock.Paragraph.class, SemanticBlock.Paragraph.class),
                document.blocks().stream().map(Object::getClass).toList());
        assertTrue(document.fallbackText().contains("[site](https://example.invalid)"));
        assertTrue(document.fallbackText().contains("![icon](https://example.invalid/x.png)"));
        assertTrue(document.fallbackText().contains("<button"));
        assertFalse(document.diagnostics().isEmpty());
        assertTrue(document.diagnostics().stream()
                .allMatch(value -> value.code().equals("semantic_content_unsupported")));
    }

    @Test
    void malformedMarkupRemainsReadableLiteralText() {
        SemanticDocument document = parser.parse("Keep **unfinished and `open");

        assertEquals("Keep **unfinished and `open", document.fallbackText());
        SemanticBlock.Paragraph paragraph = assertInstanceOf(
                SemanticBlock.Paragraph.class, document.blocks().getFirst());
        assertTrue(paragraph.content().stream().allMatch(SemanticInline.Text.class::isInstance));
    }

    @Test
    void nodeIdsAreStableForTheSameDocumentAndChangeWithContent() {
        SemanticDocument first = parser.parse("Hello **world**");
        SemanticDocument same = parser.parse("Hello **world**");
        SemanticDocument changed = parser.parse("Hello **player**");

        assertEquals(first.blocks().getFirst().nodeId(), same.blocks().getFirst().nodeId());
        assertFalse(first.blocks().getFirst().nodeId().equals(
                changed.blocks().getFirst().nodeId()));
    }
}
