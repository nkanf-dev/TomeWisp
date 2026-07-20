package dev.openallay.guide.ui;

import com.google.gson.JsonObject;
import dev.openallay.guide.GuideToolMessage;
import dev.openallay.guide.GuideToolActivity;
import dev.openallay.guide.GuideToolPresentation;
import java.util.List;

/** First-class concise views for grounded tools, with deterministic JSON fallback. */
public final class GuideToolPresenter {
    private GuideToolPresenter() {}

    public static List<GuideToolMessage> messages(String toolId, JsonObject normalized) {
        return GuideToolPresentation.messages(toolId, normalized);
    }

    public static List<GuideToolMessage> messages(GuideToolActivity activity) {
        java.util.Objects.requireNonNull(activity, "activity");
        if (toolName(activity.toolId()).startsWith("resource_")
                && !activity.uiReference().summary().operation().isBlank()) {
            return GuideToolPresentation.resourceMessages(activity.uiReference());
        }
        java.util.ArrayList<GuideToolMessage> messages = new java.util.ArrayList<>(
                GuideToolPresentation.messages(activity.toolId(), activity.normalized()));
        if (activity.uiReference().continuationAvailable()) {
            messages.add(GuideToolMessage.of(GuideToolMessage.Key.RESOURCE_RESULT_CONTINUATION));
        }
        return List.copyOf(messages);
    }

    private static String toolName(String toolId) {
        int separator = toolId.indexOf(':');
        return separator < 0 ? toolId : toolId.substring(separator + 1);
    }
}
