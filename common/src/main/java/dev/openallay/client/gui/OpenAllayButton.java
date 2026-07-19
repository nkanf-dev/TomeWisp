package dev.openallay.client.gui;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** A compact pixel-style button that keeps Minecraft's input and narration behavior. */
public final class OpenAllayButton extends Button {
    private boolean selected;

    private OpenAllayButton(
            int x,
            int y,
            int width,
            int height,
            Component message,
            OnPress onPress,
            CreateNarration createNarration,
            boolean selected) {
        super(x, y, width, height, message, onPress, createNarration);
        this.selected = selected;
    }

    public static Builder create(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    public boolean selected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    protected void extractContents(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        OpenAllayWidgetTheme.ButtonVisualState state = OpenAllayWidgetTheme.buttonState(
                active, isHovered(), isFocused(), selected);
        OpenAllayWidgetTheme.ButtonColors colors = OpenAllayWidgetTheme.buttonColors(state);
        int x = getX();
        int y = getY();
        int width = getWidth();
        int height = getHeight();

        // One-pixel shadow and border keep the silhouette crisp at every GUI scale.
        graphics.fill(x + 1, y + height - 1, x + width + 1, y + height + 1, colors.shadow());
        graphics.fill(x, y, x + width, y + height, colors.fill());
        graphics.outline(x, y, width, height, colors.border());

        if (state == OpenAllayWidgetTheme.ButtonVisualState.HOVERED
                || state == OpenAllayWidgetTheme.ButtonVisualState.FOCUSED
                || state == OpenAllayWidgetTheme.ButtonVisualState.SELECTED) {
            int markerBottom = Math.max(y + 2, y + height - 2);
            graphics.fill(x + 1, y + 2, x + 3, markerBottom, colors.marker());
        }
        if (state == OpenAllayWidgetTheme.ButtonVisualState.FOCUSED) {
            graphics.fill(x + width - 3, y + 1, x + width - 1, y + 3, colors.marker());
            graphics.fill(
                    x + width - 3,
                    y + height - 3,
                    x + width - 1,
                    y + height - 1,
                    colors.marker());
        }

        extractScrollingStringOverContents(
                graphics.textRendererForWidget(
                        this, GuiGraphicsExtractor.HoveredTextEffects.NONE),
                getMessage().copy().withColor(colors.text()),
                4);
    }

    public static final class Builder {
        private final Component message;
        private final OnPress onPress;
        private Tooltip tooltip;
        private int x;
        private int y;
        private int width = DEFAULT_WIDTH;
        private int height = DEFAULT_HEIGHT;
        private CreateNarration createNarration = DEFAULT_NARRATION;
        private boolean selected;

        private Builder(Component message, OnPress onPress) {
            this.message = Objects.requireNonNull(message, "message");
            this.onPress = Objects.requireNonNull(onPress, "onPress");
        }

        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder bounds(int x, int y, int width, int height) {
            return pos(x, y).size(width, height);
        }

        public Builder tooltip(Tooltip tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public Builder createNarration(CreateNarration createNarration) {
            this.createNarration = Objects.requireNonNull(createNarration, "createNarration");
            return this;
        }

        public Builder selected(boolean selected) {
            this.selected = selected;
            return this;
        }

        public OpenAllayButton build() {
            OpenAllayButton button = new OpenAllayButton(
                    x, y, width, height, message, onPress, createNarration, selected);
            button.setTooltip(tooltip);
            return button;
        }
    }
}
