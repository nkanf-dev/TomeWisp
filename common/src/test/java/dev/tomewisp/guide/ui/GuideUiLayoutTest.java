package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GuideUiLayoutTest {
    @Test
    void normalLayoutKeepsRailAndInlineDetailOutsideTranscript() {
        GuideUiLayout layout = GuideUiLayout.calculate(900, 500, true);
        assertFalse(layout.narrow());
        assertFalse(layout.detailOverlay());
        assertTrue(layout.sessionRail().width() > 0);
        assertTrue(layout.transcript().x() > layout.sessionRail().x() + layout.sessionRail().width());
        assertTrue(layout.detail().x() >= layout.transcript().x() + layout.transcript().width());
        assertTrue(layout.progress().height() > 0);
        assertTrue(layout.transcript().y() + layout.transcript().height() <= layout.progress().y());
        assertTrue(layout.progress().y() + layout.progress().height() <= layout.composer().y());
    }

    @Test
    void narrowLayoutUsesOverlayWithoutShrinkingTranscriptToNothing() {
        GuideUiLayout layout = GuideUiLayout.calculate(320, 240, true);
        assertTrue(layout.narrow());
        assertTrue(layout.detailOverlay());
        assertTrue(layout.sessionRail().width() == 0);
        assertTrue(layout.transcript().width() > 250);
        assertTrue(layout.detail().width() <= 320 - 16);
        assertTrue(layout.progress().width() == layout.composer().width());
    }
}
