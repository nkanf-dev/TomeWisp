package dev.tomewisp.guide.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SemanticStreamingStateTest {
    private final SemanticMessageParser parser = new SemanticMessageParser();

    @Test
    void reusesCompletedPrefixAndReparsesOnlyMutableTail() {
        SemanticStreamingState first = SemanticStreamingState.empty().update(
                "First *done*.\n\nSecond **open", false, parser);
        SemanticBlock completed = first.completedBlocks().getFirst();
        assertEquals("First done.\n\nSecond **open", first.document().fallbackText());

        SemanticStreamingState second = first.update(
                "First *done*.\n\nSecond **closed**", false, parser);

        assertSame(completed, second.completedBlocks().getFirst());
        assertTrue(((SemanticBlock.Paragraph) second.document().blocks().get(1))
                .content().stream().anyMatch(SemanticInline.Strong.class::isInstance));
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
