package dev.openallay.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

final class OpenAllayWidgetThemeTest {
    @Test
    void everyInteractiveStateHasAnUnambiguousVisualTreatment() {
        assertEquals(
                OpenAllayWidgetTheme.ButtonVisualState.IDLE,
                OpenAllayWidgetTheme.buttonState(true, false, false, false));
        assertEquals(
                OpenAllayWidgetTheme.ButtonVisualState.HOVERED,
                OpenAllayWidgetTheme.buttonState(true, true, false, false));
        assertEquals(
                OpenAllayWidgetTheme.ButtonVisualState.FOCUSED,
                OpenAllayWidgetTheme.buttonState(true, false, true, false));
        assertEquals(
                OpenAllayWidgetTheme.ButtonVisualState.DISABLED,
                OpenAllayWidgetTheme.buttonState(false, true, true, false));
        assertEquals(
                OpenAllayWidgetTheme.ButtonVisualState.SELECTED,
                OpenAllayWidgetTheme.buttonState(false, false, false, true));

        for (OpenAllayWidgetTheme.ButtonVisualState state
                : OpenAllayWidgetTheme.ButtonVisualState.values()) {
            OpenAllayWidgetTheme.ButtonColors colors =
                    OpenAllayWidgetTheme.buttonColors(state);
            assertNotEquals(colors.fill(), colors.border(), state.name());
        }
    }

    @Test
    void focusUsesAmberAndSelectionUsesMint() {
        assertEquals(
                OpenAllayWidgetTheme.AMBER,
                OpenAllayWidgetTheme.buttonColors(
                                OpenAllayWidgetTheme.ButtonVisualState.FOCUSED)
                        .border());
        assertEquals(
                OpenAllayWidgetTheme.MINT,
                OpenAllayWidgetTheme.buttonColors(
                                OpenAllayWidgetTheme.ButtonVisualState.SELECTED)
                        .border());
    }
}
