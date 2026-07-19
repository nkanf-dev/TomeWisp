package dev.tomewisp.client.gui;

import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.guide.semantic.RecipeSemanticHandle;
import dev.tomewisp.guide.semantic.RichComponent;
import dev.tomewisp.guide.semantic.SemanticReference;
import dev.tomewisp.guide.semantic.SemanticReferenceKind;
import dev.tomewisp.guide.ui.GuideUiLayout;
import dev.tomewisp.guide.ui.SemanticLayout;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

/** Native renderer for safe semantic layouts. It emits typed intents, never callbacks from text. */
public final class MinecraftSemanticRenderer {
    private static final int TEXT = 0xFFE8EDF2;
    private static final int MUTED = 0xFFA9B3BE;
    private static final int ACCENT = 0xFF72D5C4;
    private static final int PANEL = 0xA0242933;
    private static final int SUCCESS = 0xFF7FC8A9;
    private static final int ERROR = 0xFFFF7D7D;

    public sealed interface Intent permits Intent.BrowseRecipes, Intent.BrowseUsages,
            Intent.ExactRecipe, Intent.Source, Intent.Evidence, Intent.Choice {
        record BrowseRecipes(String itemId) implements Intent {}
        record BrowseUsages(String itemId) implements Intent {}
        record ExactRecipe(RecipeReference reference) implements Intent {}
        record Source(String sourceId, String originInvocationId) implements Intent {}
        record Evidence(String evidenceId, String originInvocationId) implements Intent {}
        record Choice(String componentNodeId, String choiceId) implements Intent {}
    }

    public record Hit(GuideUiLayout.Rect bounds, Intent intent) {}
    public record Result(int bottom, List<Hit> hits) {
        public Result { hits = List.copyOf(hits); }
    }

    private final MinecraftSemanticResolver resolver;

    public MinecraftSemanticRenderer(MinecraftSemanticResolver resolver) {
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
    }

    public Result render(
            GuiGraphicsExtractor graphics,
            Font font,
            SemanticLayout layout,
            int x,
            int y,
            int width,
            int mouseX,
            int mouseY) {
        return render(graphics, font, layout, x, y, width, mouseX, mouseY, false, 0);
    }

    public Result render(
            GuiGraphicsExtractor graphics,
            Font font,
            SemanticLayout layout,
            int x,
            int y,
            int width,
            int mouseX,
            int mouseY,
            boolean animationsEnabled,
            long presentationTicks) {
        ArrayList<Hit> hits = new ArrayList<>();
        int current = y;
        for (SemanticLayout.Line line : layout.lines()) {
            int lineTop = current;
            int left = x + line.indent();
            switch (line.kind()) {
                case RULE -> graphics.fill(left, current + line.height() / 2,
                        x + width, current + line.height() / 2 + 1, MUTED);
                case COMPONENT -> renderComponent(
                        graphics, font, line.component(), left, current,
                        Math.max(20, width - line.indent()), mouseX, mouseY, hits,
                        animationsEnabled, presentationTicks);
                default -> {
                    if (line.kind() == SemanticLayout.Kind.CODE) {
                        graphics.fill(left - 2, current - 1, x + width, current + line.height(), PANEL);
                    } else if (line.kind() == SemanticLayout.Kind.QUOTE) {
                        graphics.fill(left - 4, current, left - 2, current + line.height(), ACCENT);
                    }
                    MutableComponent rendered = Component.empty();
                    int runX = left;
                    for (SemanticLayout.Run run : line.runs()) {
                        MutableComponent value = Component.literal(run.text());
                        value = switch (run.style()) {
                            case NORMAL -> value;
                            case EMPHASIS -> value.withStyle(ChatFormatting.ITALIC);
                            case STRONG -> value.withStyle(ChatFormatting.BOLD);
                            case CODE -> value.withStyle(ChatFormatting.GRAY);
                            case REFERENCE -> value.withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE);
                        };
                        rendered.append(value);
                        int runWidth = font.width(value);
                        Intent intent = intent(run.reference());
                        if (intent != null) {
                            hits.add(new Hit(
                                    new GuideUiLayout.Rect(runX, current - 1, runWidth, line.height()),
                                    intent));
                        }
                        runX += runWidth;
                    }
                    graphics.text(font, rendered, left, current,
                            line.kind() == SemanticLayout.Kind.HEADING ? ACCENT : TEXT, false);
                }
            }
            current = lineTop + line.height();
        }
        return new Result(current, hits);
    }

    public static Intent intent(SemanticReference reference) {
        if (reference == null) return null;
        return switch (reference.kind()) {
            case ITEM, BLOCK -> new Intent.BrowseRecipes(reference.target());
            case RECIPE -> {
                if (!reference.grounded()) yield null;
                try {
                    yield new Intent.ExactRecipe(RecipeSemanticHandle.decode(reference.target()));
                } catch (IllegalArgumentException malformed) {
                    yield null;
                }
            }
            case SOURCE -> reference.grounded()
                    ? new Intent.Source(reference.target(), reference.originInvocationId()) : null;
            case EVIDENCE -> reference.grounded()
                    ? new Intent.Evidence(reference.target(), reference.originInvocationId()) : null;
            case FLUID, ENTITY, BIOME, DIMENSION, TAG, KEY -> null;
        };
    }

    private void renderComponent(
            GuiGraphicsExtractor graphics,
            Font font,
            RichComponent component,
            int x,
            int y,
            int width,
            int mouseX,
            int mouseY,
            List<Hit> hits,
            boolean animationsEnabled,
            long presentationTicks) {
        graphics.fill(x - 2, y - 1, x + width, y + componentHeight(component), PANEL);
        switch (component) {
            case RichComponent.ItemRow value -> {
                int rowY = y;
                for (RichComponent.Item item : value.items()) {
                    renderItem(graphics, font, item.itemId(), item.label(), item.count(),
                            x + 2, rowY, mouseX, mouseY);
                    int actionX = Math.min(x + width - 54, x + 120);
                    action(graphics, font, Component.translatable(
                                    "screen.tomewisp.semantic.action.recipes"), actionX, rowY + 4,
                            new Intent.BrowseRecipes(item.itemId()), hits);
                    action(graphics, font, Component.translatable(
                                    "screen.tomewisp.semantic.action.usages"), actionX + 28, rowY + 4,
                            new Intent.BrowseUsages(item.itemId()), hits);
                    rowY += 22;
                }
            }
            case RichComponent.RecipeGrid value -> {
                graphics.text(font, value.label().isBlank()
                                ? Component.translatable("screen.tomewisp.semantic.recipe")
                                : Component.literal(value.label()),
                        x + 4, y + 4, ACCENT, false);
                graphics.text(font, Component.translatable(
                                "screen.tomewisp.semantic.recipe_verified"),
                        x + 4, y + 18, MUTED, false);
                action(graphics, font, Component.translatable(
                                "screen.tomewisp.semantic.action.open_recipe"), x + 4, y + 34,
                        new Intent.ExactRecipe(value.recipe()), hits);
            }
            case RichComponent.IngredientCheck value -> {
                int rowY = y;
                for (RichComponent.Ingredient ingredient : value.ingredients()) {
                    renderItem(graphics, font, ingredient.itemId(), ingredient.label(),
                            ingredient.required(), x + 2, rowY, mouseX, mouseY);
                    String count = ingredient.available() + "/" + ingredient.required();
                    graphics.text(font, count, x + width - font.width(count) - 5, rowY + 5,
                            ingredient.available() >= ingredient.required() ? SUCCESS : ERROR, false);
                    rowY += 22;
                }
            }
            case RichComponent.CraftabilitySummary value -> {
                graphics.text(font, Component.translatable(value.craftable()
                                ? "screen.tomewisp.semantic.craftable"
                                : "screen.tomewisp.semantic.not_craftable"),
                        x + 4, y + 4, value.craftable() ? SUCCESS : ERROR, false);
                graphics.text(font, Component.translatable(
                                "screen.tomewisp.semantic.maximum_crafts", value.maximumCrafts()),
                        x + 4, y + 16, TEXT, false);
                graphics.text(font, Component.translatable(value.conclusive()
                                ? "screen.tomewisp.semantic.conclusive"
                                : "screen.tomewisp.semantic.incomplete"),
                        x + 4, y + 28, MUTED, false);
            }
            case RichComponent.ProgressSteps value -> {
                int rowY = y + 2;
                for (RichComponent.Step step : value.steps()) {
                    String marker = progressMarker(
                            step.state(), animationsEnabled, presentationTicks);
                    graphics.text(font, marker + " " + step.label(), x + 4, rowY,
                            step.state() == RichComponent.StepState.FAILED ? ERROR : TEXT, false);
                    rowY += 10;
                }
            }
            case RichComponent.SourceSummary value -> {
                int rowY = y + 2;
                for (RichComponent.Source source : value.sources()) {
                    graphics.text(font, Component.translatable(
                                    "screen.tomewisp.semantic.source", source.label()),
                            x + 4, rowY, ACCENT, false);
                    hits.add(new Hit(new GuideUiLayout.Rect(x + 2, rowY - 1, width - 4, 11),
                            new Intent.Source(source.sourceId(), source.originInvocationId())));
                    rowY += 10;
                }
            }
            case RichComponent.StatusBadge value -> graphics.text(
                    font, value.label(), x + 5, y + 4,
                    switch (value.state()) {
                        case INFO -> ACCENT;
                        case SUCCESS -> SUCCESS;
                        case WARNING -> 0xFFFFD479;
                        case ERROR -> ERROR;
                    }, false);
            case RichComponent.ChoiceGroup value -> {
                graphics.text(font, value.prompt(), x + 4, y + 2, TEXT, false);
                int rowY = y + 14;
                for (RichComponent.Choice choice : value.choices()) {
                    action(graphics, font, Component.literal(choice.label()), x + 4, rowY,
                            new Intent.Choice(value.nodeId(), choice.id()), hits);
                    rowY += 11;
                }
            }
        }
    }

    static String progressMarker(
            RichComponent.StepState state,
            boolean animationsEnabled,
            long presentationTicks) {
        return switch (state) {
            case PENDING -> "○";
            case ACTIVE -> animationsEnabled && (presentationTicks / 8) % 2 == 0
                    ? "▷" : "▶";
            case COMPLETE -> "✓";
            case FAILED -> "!";
        };
    }

    private void renderItem(
            GuiGraphicsExtractor graphics,
            Font font,
            String itemId,
            String label,
            long count,
            int x,
            int y,
            int mouseX,
            int mouseY) {
        MinecraftSemanticResolver.ItemPresentation item = resolver.item(itemId, label, count);
        ItemStack stack = item.stack();
        if (item.resolved()) {
            graphics.item(stack, x, y);
            graphics.itemDecorations(font, stack, x, y);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
            }
        } else {
            graphics.text(font, "?", x + 5, y + 4, MUTED, false);
        }
        graphics.text(font, item.label() + (count > 1 ? " ×" + count : ""),
                x + 20, y + 5, TEXT, false);
    }

    private static void action(
            GuiGraphicsExtractor graphics,
            Font font,
            Component label,
            int x,
            int y,
            Intent intent,
            List<Hit> hits) {
        int width = font.width(label) + 6;
        graphics.fill(x, y - 2, x + width, y + 9, 0xFF29443F);
        graphics.text(font, label, x + 3, y, ACCENT, false);
        hits.add(new Hit(new GuideUiLayout.Rect(x, y - 2, width, 11), intent));
    }

    private static int componentHeight(RichComponent component) {
        return switch (component) {
            case RichComponent.ItemRow value -> Math.max(22, 22 * value.items().size());
            case RichComponent.RecipeGrid ignored -> 54;
            case RichComponent.IngredientCheck value -> Math.max(22, 22 * value.ingredients().size());
            case RichComponent.CraftabilitySummary ignored -> 40;
            case RichComponent.ProgressSteps value -> 12 * (value.steps().size() + 1);
            case RichComponent.SourceSummary value -> 12 * (value.sources().size() + 1);
            case RichComponent.StatusBadge ignored -> 16;
            case RichComponent.ChoiceGroup value -> 12 * (value.choices().size() + 1);
        };
    }
}
