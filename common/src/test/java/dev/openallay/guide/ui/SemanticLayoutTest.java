package dev.openallay.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.guide.semantic.SemanticDocument;
import dev.openallay.guide.semantic.SemanticBlock;
import dev.openallay.guide.semantic.SemanticMessageParser;
import dev.openallay.guide.semantic.SemanticStreamingState;
import java.util.Locale;
import org.junit.jupiter.api.Test;

final class SemanticLayoutTest {
    private static final SemanticLayoutEngine.Measurer MEASURER = new SemanticLayoutEngine.Measurer() {
        @Override public int width(String text, SemanticLayout.Style style) {
            return text.codePointCount(0, text.length()) * (style == SemanticLayout.Style.STRONG ? 2 : 1);
        }
        @Override public int lineHeight(SemanticLayout.Kind kind) {
            return kind == SemanticLayout.Kind.HEADING ? 14 : 10;
        }
    };

    @Test
    void laysOutSupportedSemanticBlocksAndWrapsWithoutLosingNarration() {
        SemanticDocument document = new SemanticMessageParser().parse("""
                # Heading

                - first item
                - second **strong** item

                > quoted text

                | A | B |
                |---|---|
                | C | D |

                ```java
                value();
                ```
                """);

        SemanticLayout layout = new SemanticLayoutEngine().layout(document, 12, MEASURER);

        assertTrue(layout.lines().stream().anyMatch(line -> line.kind() == SemanticLayout.Kind.HEADING));
        assertTrue(layout.lines().stream().anyMatch(line -> line.kind() == SemanticLayout.Kind.TABLE));
        assertTrue(layout.lines().stream().anyMatch(line -> line.kind() == SemanticLayout.Kind.CODE));
        assertEquals(document.fallbackText(), layout.narration());
        assertEquals(layout.height(), layout.lines().stream().mapToInt(SemanticLayout.Line::height).sum());
    }

    @Test
    void listMarkerSharesFirstBaselineAndWrappedTextUsesMeasuredHangingIndent() {
        SemanticDocument document = new SemanticMessageParser().parse("""
                - abcdefghijklmnop
                - short
                """);

        SemanticLayout layout = new SemanticLayoutEngine().layout(document, 12, MEASURER);

        SemanticLayout.Line first = layout.lines().getFirst();
        assertEquals("• ", first.runs().getFirst().text());
        assertTrue(first.runs().stream().map(SemanticLayout.Run::text)
                .reduce("", String::concat).startsWith("• a"));
        int measuredMarkerWidth = MEASURER.width("• ", SemanticLayout.Style.STRONG);
        assertEquals(measuredMarkerWidth, layout.lines().get(1).indent());
        assertTrue(layout.lines().stream()
                .filter(line -> line.runs().stream().anyMatch(run -> run.text().equals("• ")))
                .count() == 2);
    }

    @Test
    void nestedAndMultiParagraphListsIndentWithoutRepeatingParentMarker() {
        SemanticDocument document = new SemanticMessageParser().parse("""
                9. parent paragraph

                   second paragraph

                   - nested item that wraps
                """);

        SemanticLayout layout = new SemanticLayoutEngine().layout(document, 18, MEASURER);

        assertTrue(layout.lines().getFirst().runs().stream()
                .map(SemanticLayout.Run::text).reduce("", String::concat).startsWith("9. parent"));
        assertEquals(1, layout.lines().stream()
                .filter(line -> line.runs().stream().anyMatch(run -> run.text().equals("9. ")))
                .count());
        SemanticLayout.Line nested = layout.lines().stream()
                .filter(line -> line.runs().stream().anyMatch(run -> run.text().equals("• ")))
                .findFirst().orElseThrow();
        assertTrue(nested.indent() >= MEASURER.width("9. ", SemanticLayout.Style.STRONG));
    }

    @Test
    void wideTablesPreserveGridCellsAlignmentWrappingAndReferences() {
        SemanticDocument document = new SemanticMessageParser().parse("""
                | Item | Count | Note |
                |:-----|------:|:----:|
                | [[tw:item\\|minecraft:iron_ingot\\|Iron]] | 9 | a deliberately long note that wraps |
                | Coal | 1 | Fuel |
                """);

        SemanticLayout layout = new SemanticLayoutEngine().layout(document, 180, MEASURER);

        SemanticLayout.Line line = layout.lines().getFirst();
        SemanticLayout.TableBox table = line.table();
        assertEquals(SemanticLayout.Kind.TABLE, line.kind());
        assertEquals(SemanticLayout.TableBox.Mode.GRID, table.mode());
        assertEquals(3, table.rows().size());
        assertTrue(table.rows().getFirst().header());
        assertEquals(180, table.rows().getFirst().cells().stream()
                .mapToInt(SemanticLayout.TableCell::width).sum());
        assertEquals(SemanticBlock.Alignment.LEFT,
                table.rows().get(1).cells().getFirst().alignment());
        assertEquals(SemanticBlock.Alignment.RIGHT,
                table.rows().get(1).cells().get(1).alignment());
        assertEquals(SemanticBlock.Alignment.CENTER,
                table.rows().get(1).cells().get(2).alignment());
        assertTrue(table.rows().get(1).height() > table.lineHeight());
        assertTrue(table.rows().get(1).cells().getFirst().valueLines().stream()
                .flatMap(value -> value.runs().stream())
                .anyMatch(run -> run.reference() != null));
        assertEquals(table.height(), line.height());
    }

    @Test
    void narrowTablesDeterministicallyBecomeHeaderValueCards() {
        SemanticDocument document = new SemanticMessageParser().parse("""
                | Item | Count |
                |:-----|------:|
                | Iron | 9 |
                | Coal | 1 |
                """);
        SemanticLayoutEngine engine = new SemanticLayoutEngine();

        SemanticLayout first = engine.layout(document, 80, MEASURER);
        SemanticLayout second = engine.layout(document, 80, MEASURER);

        SemanticLayout.TableBox table = first.lines().getFirst().table();
        assertEquals(SemanticLayout.TableBox.Mode.KEY_VALUE_CARDS, table.mode());
        assertEquals(2, table.rows().size());
        assertEquals(2, table.rows().getFirst().cells().size());
        assertTrue(table.rows().getFirst().cells().stream()
                .allMatch(cell -> !cell.labelLines().isEmpty()));
        assertEquals(SemanticBlock.Alignment.RIGHT,
                table.rows().getFirst().cells().get(1).alignment());
        assertEquals(first, second);
        assertEquals(table.height(), first.height());
    }

    @Test
    void characterStreamTailHeightNeverShrinksBeforeBlockValidation() {
        SemanticMessageParser parser = new SemanticMessageParser();
        SemanticStreamingState state = SemanticStreamingState.empty();
        String source = "- first streamed item\n- second streamed item";
        int previousHeight = 0;

        for (int length = 1; length <= source.length(); length++) {
            int position = length;
            state = state.update(source.substring(0, length), false, parser);
            int height = new SemanticLayoutEngine()
                    .layout(state.document(), 11, MEASURER).height();
            assertTrue(height >= previousHeight,
                    () -> "streaming height shrank at character " + position);
            previousHeight = height;
        }
    }

    @Test
    void cachesByPresentationIdentityAndInvalidatesOneRow() {
        SemanticLayoutCache cache = new SemanticLayoutCache();
        SemanticDocument document = new SemanticMessageParser().parse("Hello **world**");
        String locale = Locale.SIMPLIFIED_CHINESE.toLanguageTag();

        SemanticLayout first = cache.get(
                "assistant-1", document, 80, locale, "font", MEASURER);
        SemanticLayout second = cache.get(
                "assistant-1", document, 80, locale, "font", MEASURER);
        assertSame(first, second);
        assertEquals(new SemanticLayoutCache.Stats(1, 1, 1), cache.stats());

        cache.invalidateRow("assistant-1");
        SemanticLayout third = cache.get(
                "assistant-1", document, 80, locale, "font", MEASURER);
        assertTrue(first != third);
        assertEquals(new SemanticLayoutCache.Stats(1, 2, 1), cache.stats());
    }
}
