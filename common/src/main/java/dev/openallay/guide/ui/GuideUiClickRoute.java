package dev.openallay.guide.ui;

import java.util.List;
import java.util.Objects;

/** Resolves detail-panel clicks without allowing the panel dismissal to swallow its actions. */
public record GuideUiClickRoute(Kind kind, int actionIndex) {
    public enum Kind { ACTION, DISMISS_DETAIL, OUTSIDE_DETAIL }

    public GuideUiClickRoute {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.ACTION) != (actionIndex >= 0)) {
            throw new IllegalArgumentException("only action routes have an action index");
        }
    }

    public static GuideUiClickRoute resolveDetail(
            GuideUiLayout.Rect detail,
            List<GuideUiLayout.Rect> actions,
            double x,
            double y) {
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(actions, "actions");
        if (!detail.contains(x, y)) {
            return new GuideUiClickRoute(Kind.OUTSIDE_DETAIL, -1);
        }
        for (int index = 0; index < actions.size(); index++) {
            GuideUiLayout.Rect action = Objects.requireNonNull(actions.get(index), "action");
            if (action.contains(x, y)) {
                return new GuideUiClickRoute(Kind.ACTION, index);
            }
        }
        return new GuideUiClickRoute(Kind.DISMISS_DETAIL, -1);
    }
}
