package dev.tomewisp.guide.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SemanticStreamingStateTest {
    private final SemanticMessageParser parser = new SemanticMessageParser();

    @Test
    void reusesCompletedPrefixAndKeepsMutableTailLiteral() {
        SemanticStreamingState first = SemanticStreamingState.empty().update(
                "First *done*.\n\nSecond **open", false, parser);
        SemanticBlock completed = first.completedBlocks().getFirst();
        assertEquals("First done.\n\nSecond **open", first.document().fallbackText());

        SemanticStreamingState second = first.update(
                "First *done*.\n\nSecond **closed**", false, parser);

        assertSame(completed, second.completedBlocks().getFirst());
        SemanticBlock.Paragraph literalTail =
                (SemanticBlock.Paragraph) second.document().blocks().get(1);
        assertFalse(literalTail.content().stream()
                .anyMatch(SemanticInline.Strong.class::isInstance));
        assertEquals("Second **closed**", ((SemanticInline.Text) literalTail.content().getFirst()).text());

        SemanticStreamingState validated = second.update(
                "First *done*.\n\nSecond **closed**\n\n", false, parser);
        assertSame(completed, validated.completedBlocks().getFirst());
        assertTrue(((SemanticBlock.Paragraph) validated.completedBlocks().get(1))
                .content().stream().anyMatch(SemanticInline.Strong.class::isInstance));
    }

    @Test
    void listTailDoesNotChangeSemanticKindCharacterByCharacter() {
        String source = "- first item\n- second item";
        SemanticStreamingState state = SemanticStreamingState.empty();

        for (int length = 1; length <= source.length(); length++) {
            state = state.update(source.substring(0, length), false, parser);
            assertTrue(state.completedBlocks().isEmpty());
            assertEquals(1, state.document().blocks().size());
            assertTrue(state.document().blocks().getFirst() instanceof SemanticBlock.Paragraph);
        }

        SemanticStreamingState completed = state.update(source + "\n\n", false, parser);
        assertEquals(1, completed.completedBlocks().size());
        assertTrue(completed.completedBlocks().getFirst() instanceof SemanticBlock.ListBlock);
    }

    @Test
    void closedFenceBecomesReusableWithoutWaitingForAnotherParagraph() {
        SemanticStreamingState open = SemanticStreamingState.empty().update(
                "```text\npartial", false, parser);
        assertTrue(open.completedBlocks().isEmpty());

        SemanticStreamingState closed = open.update(
                "```text\npartial\n```\n", false, parser);

        assertEquals(1, closed.completedBlocks().size());
        assertTrue(closed.document().blocks().getFirst() instanceof SemanticBlock.CodeBlock);
    }

    @Test
    void finalReconciliationDropsStaleCachedContent() {
        SemanticStreamingState streaming = SemanticStreamingState.empty().update(
                "Old paragraph.\n\nTail", false, parser);
        SemanticBlock old = streaming.completedBlocks().getFirst();

        SemanticStreamingState reconciled = streaming.update("Replacement answer", true, parser);

        assertEquals("Replacement answer", reconciled.document().fallbackText());
        assertEquals(1, reconciled.completedBlocks().size());
        assertNotSame(old, reconciled.completedBlocks().getFirst());
    }
}
