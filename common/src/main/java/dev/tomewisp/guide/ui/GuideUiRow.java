package dev.tomewisp.guide.ui;

import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuidePersistenceSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.semantic.SemanticDocument;
import java.util.List;
import java.util.UUID;

/** Visible transcript projection. Reasoning is deliberately not representable. */
public sealed interface GuideUiRow
        permits GuideUiRow.Persistence, GuideUiRow.User, GuideUiRow.Assistant,
                GuideUiRow.Tool, GuideUiRow.Status {

    record Persistence(
            GuidePersistenceSnapshot.State state,
            String translationKey,
            GuideFailure failure) implements GuideUiRow {}

    record User(UUID requestId, String text) implements GuideUiRow {}

    record Assistant(
            UUID requestId,
            int ordinal,
            String text,
            SemanticDocument semantic,
            boolean streaming,
            List<GuideSource> sources) implements GuideUiRow {
        public Assistant {
            java.util.Objects.requireNonNull(semantic, "semantic");
            sources = List.copyOf(sources);
        }
    }

    record Tool(
            UUID requestId,
            int ordinal,
            GuideToolActivity activity,
            GuideToolDetailView detail) implements GuideUiRow {
        public Tool {
            java.util.Objects.requireNonNull(activity, "activity");
            java.util.Objects.requireNonNull(detail, "detail");
        }
    }

    record Status(
            UUID requestId,
            GuideRequestStatus status,
            String text,
            GuideFailure failure) implements GuideUiRow {}
}
