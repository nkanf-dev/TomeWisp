package dev.tomewisp.guide.ui;

import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideToolActivity;
import java.util.List;
import java.util.UUID;

/** Visible transcript projection. Reasoning is deliberately not representable. */
public sealed interface GuideUiRow
        permits GuideUiRow.User, GuideUiRow.Assistant, GuideUiRow.Tool, GuideUiRow.Status {
    UUID requestId();

    record User(UUID requestId, String text) implements GuideUiRow {}

    record Assistant(
            UUID requestId,
            String text,
            boolean streaming,
            List<GuideSource> sources) implements GuideUiRow {
        public Assistant { sources = List.copyOf(sources); }
    }

    record Tool(UUID requestId, GuideToolActivity activity) implements GuideUiRow {}

    record Status(
            UUID requestId,
            GuideRequestStatus status,
            String text,
            GuideFailure failure) implements GuideUiRow {}
}
