package dev.openallay.client.gui;

/** Shared hard-edged palette for OpenAllay's native Minecraft widgets. */
public final class OpenAllayWidgetTheme {
    public static final int CHARCOAL = 0xFF181B22;
    public static final int CHARCOAL_RAISED = 0xFF242933;
    public static final int CHARCOAL_HOVERED = 0xFF2B3B3B;
    public static final int CHARCOAL_DISABLED = 0xFF202329;
    public static final int SLATE_BORDER = 0xFF4A5561;
    public static final int SLATE_DISABLED = 0xFF343A42;
    public static final int MINT = 0xFF72D5C4;
    public static final int MINT_DARK = 0xFF355F59;
    public static final int AMBER = 0xFFF0B85B;
    public static final int WHITE = 0xFFE8EDF2;
    public static final int MUTED = 0xFF7F8994;

    private OpenAllayWidgetTheme() {}

    public static ButtonVisualState buttonState(
            boolean active,
            boolean hovered,
            boolean focused,
            boolean selected) {
        if (selected) {
            return ButtonVisualState.SELECTED;
        }
        if (!active) {
            return ButtonVisualState.DISABLED;
        }
        if (focused) {
            return ButtonVisualState.FOCUSED;
        }
        if (hovered) {
            return ButtonVisualState.HOVERED;
        }
        return ButtonVisualState.IDLE;
    }

    public static ButtonColors buttonColors(ButtonVisualState state) {
        return switch (state) {
            case IDLE -> new ButtonColors(
                    CHARCOAL_RAISED, SLATE_BORDER, CHARCOAL, WHITE, SLATE_BORDER);
            case HOVERED -> new ButtonColors(
                    CHARCOAL_HOVERED, MINT, CHARCOAL, WHITE, MINT);
            case FOCUSED -> new ButtonColors(
                    CHARCOAL_HOVERED, AMBER, CHARCOAL, WHITE, AMBER);
            case SELECTED -> new ButtonColors(
                    MINT_DARK, MINT, CHARCOAL, WHITE, MINT);
            case DISABLED -> new ButtonColors(
                    CHARCOAL_DISABLED, SLATE_DISABLED, CHARCOAL, MUTED, SLATE_DISABLED);
        };
    }

    public enum ButtonVisualState {
        IDLE,
        HOVERED,
        FOCUSED,
        SELECTED,
        DISABLED
    }

    public record ButtonColors(int fill, int border, int shadow, int text, int marker) {}
}
