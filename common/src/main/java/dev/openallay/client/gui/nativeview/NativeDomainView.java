package dev.openallay.client.gui.nativeview;

import dev.openallay.guide.ui.GuideUiLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Visible client-thread object; never persisted or exposed to model context. */
public interface NativeDomainView extends AutoCloseable {
    String providerId();

    NativeDomainViewBinding.Family family();

    default void tick() {}

    void render(RenderContext context);

    @Override
    default void close() {}

    record RenderContext(
            GuiGraphicsExtractor graphics,
            Font font,
            GuideUiLayout.Rect bounds,
            int mouseX,
            int mouseY,
            long presentationTicks) {
        public RenderContext {
            java.util.Objects.requireNonNull(graphics, "graphics");
            java.util.Objects.requireNonNull(font, "font");
            java.util.Objects.requireNonNull(bounds, "bounds");
        }
    }
}
