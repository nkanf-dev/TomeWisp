package dev.openallay.client.gui.nativeview;

import dev.openallay.guide.semantic.RichComponent;
import dev.openallay.guide.ui.GuideRecipeCard;
import dev.openallay.guide.ui.GuideRecipePresenter;
import dev.openallay.guide.ui.GuideUiRow;
import dev.openallay.guide.ui.GuideUiView;
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
                .flatMap(tool -> GuideRecipePresenter.cards(tool.activity()).stream())
                .filter(card -> card.references().contains(component.recipe()))
                .findFirst()
                .map(card -> new NativeDomainViewBinding.Recipe(
                        "assistant:" + assistant.requestId() + ":" + assistant.ordinal()
                                + ":component:" + component.nodeId(),
                        component,
                        card));
    }

    /** Creates a native binding directly from validated Tool truth, without model-authored UI. */
    public static NativeDomainViewBinding.Recipe recipe(
            String stableId, String invocationId, GuideRecipeCard card) {
        java.util.Objects.requireNonNull(card, "card");
        String nodeId = sha256(stableId + "\n" + card.reference());
        RichComponent.RecipeGrid component = new RichComponent.RecipeGrid(
                nodeId,
                card.reference(),
                invocationId,
                card.outputs().isEmpty() ? card.id() : card.outputs().getFirst().displayName(),
                card.id(),
                card.id());
        return new NativeDomainViewBinding.Recipe(stableId, component, card);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
