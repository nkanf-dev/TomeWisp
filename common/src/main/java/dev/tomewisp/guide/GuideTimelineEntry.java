package dev.tomewisp.guide;

import java.util.List;

public sealed interface GuideTimelineEntry
        permits GuideTimelineEntry.Assistant, GuideTimelineEntry.Tool {
    int ordinal();

    record Assistant(
            int ordinal,
            String text,
            boolean streaming,
            List<GuideSource> sources) implements GuideTimelineEntry {
        public Assistant {
            requireOrdinal(ordinal);
            text = text == null ? "" : text;
            sources = List.copyOf(sources);
        }
    }

    record Tool(int ordinal, GuideToolActivity activity) implements GuideTimelineEntry {
        public Tool {
            requireOrdinal(ordinal);
            java.util.Objects.requireNonNull(activity, "activity");
        }
    }

    private static void requireOrdinal(int ordinal) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("timeline ordinal must not be negative");
        }
    }
}
