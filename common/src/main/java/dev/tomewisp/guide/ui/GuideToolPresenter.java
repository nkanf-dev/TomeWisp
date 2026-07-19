package dev.tomewisp.guide.ui;

import com.google.gson.JsonObject;
import dev.tomewisp.guide.GuideToolMessage;
import dev.tomewisp.guide.GuideToolPresentation;
import java.util.List;

/** First-class concise views for grounded tools, with deterministic JSON fallback. */
public final class GuideToolPresenter {
    private GuideToolPresenter() {}

    public static List<GuideToolMessage> messages(String toolId, JsonObject normalized) {
        return GuideToolPresentation.messages(toolId, normalized);
    }
}
