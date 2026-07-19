package dev.openallay.guide.ui;

import com.google.gson.JsonObject;
import dev.openallay.guide.GuideToolMessage;
import dev.openallay.guide.GuideToolPresentation;
import java.util.List;

/** First-class concise views for grounded tools, with deterministic JSON fallback. */
public final class GuideToolPresenter {
    private GuideToolPresenter() {}

    public static List<GuideToolMessage> messages(String toolId, JsonObject normalized) {
        return GuideToolPresentation.messages(toolId, normalized);
    }
}
