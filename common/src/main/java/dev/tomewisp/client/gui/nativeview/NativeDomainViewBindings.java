package dev.tomewisp.client.gui.nativeview;

import dev.tomewisp.guide.semantic.RichComponent;
import dev.tomewisp.guide.ui.GuideRecipeCard;
import dev.tomewisp.guide.ui.GuideRecipePresenter;
import dev.tomewisp.guide.ui.GuideUiRow;
import dev.tomewisp.guide.ui.GuideUiView;
import java.util.Optional;

/** Same-request binding from a validated component to its complete normalized Tool result. */
public final class NativeDomainViewBindings {
    private NativeDomainViewBindings() {}

    public static Optional<NativeDomainViewBinding.Recipe> recipe(
            GuideUiView view,
            GuideUiRow.Assistant assistant,
            RichComponent.RecipeGrid component) {
        java.util.Objects.requireNonNull(view, "view");
        java.util.Objects.requireNonNull(assistant, "assistant");
        java.util.Objects.requireNonNull(component, "component");
        return view.rows().stream()
                .filter(GuideUiRow.Tool.class::isInstance)
                .map(GuideUiRow.Tool.class::cast)
                .filter(tool -> tool.requestId().equals(assistant.requestId()))
                .filter(tool -> tool.activity().invocationId().equals(component.originInvocationId()))
                .flatMap(tool -> GuideRecipePresenter.cards(
                        tool.activity().toolId(), tool.activity().normalized()).stream())
                .filter(card -> card.references().contains(component.recipe()))
                .findFirst()
                .map(card -> new NativeDomainViewBinding.Recipe(
                        "assistant:" + assistant.requestId() + ":" + assistant.ordinal()
                                + ":component:" + component.nodeId(),
                        component,
                        card));
    }
}
